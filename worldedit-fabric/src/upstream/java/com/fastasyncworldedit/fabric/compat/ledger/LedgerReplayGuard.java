package com.fastasyncworldedit.fabric.compat.ledger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.fastasyncworldedit.fabric.edit.CommitReceipt;
import com.fastasyncworldedit.fabric.edit.EditActorContext;
import com.fastasyncworldedit.fabric.edit.EditDelta;
import com.fastasyncworldedit.fabric.edit.EditValue;
import com.fastasyncworldedit.fabric.edit.FabricCommitCoordinator;
import com.fastasyncworldedit.fabric.edit.FabricEdits;
import com.fastasyncworldedit.fabric.edit.FabricRegionCommands;
import com.fastasyncworldedit.fabric.edit.FabricWorldAccess;
import com.fastasyncworldedit.fabric.edit.FrozenNbt;
import com.fastasyncworldedit.fabric.edit.UnsupportedBlockException;
import com.github.quiltservertools.ledger.actions.ActionType;
import com.github.quiltservertools.ledger.actions.BlockChangeActionType;
import com.github.quiltservertools.ledger.api.ReplayContext;
import com.github.quiltservertools.ledger.api.ReplayGuard;
import com.github.quiltservertools.ledger.api.ReplayMode;
import com.github.quiltservertools.ledger.api.ReplayOutcome;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.SideEffectSet;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ledger's replay through FAWE's coordinator, for every producer's actions: each change is one native
 * write as the current operator, so claims, the operator's current permission and the coordinator's
 * revision check all apply. A cell that no longer holds what its action left is a conflict, never
 * overwritten. Actions with no known footprint are refused, so no rollback bypasses protection.
 */
final class LedgerReplayGuard implements ReplayGuard {
    private final MinecraftServer server;
    private final FabricWorldAccess access;

    LedgerReplayGuard(MinecraftServer server, FabricWorldAccess access) {
        this.server = server;
        this.access = access;
    }

    /** What one action changes, as the value it must find and the value it writes. */
    private record Footprint(EditDelta.Kind kind, BlockVector3 pos, EditValue expected, EditValue target,
            boolean compareNbt) {
        boolean holds(EditValue current) {
            return compareNbt ? current.equals(expected) : current.id().equals(expected.id());
        }
    }

    @Override
    public CompletableFuture<Map<Integer, ReplayOutcome>> replay(List<ActionType> actions, ReplayContext context) {
        Map<Integer, ReplayOutcome> outcomes = new ConcurrentHashMap<>();
        if (context.operator() == null) {
            // Claims are decided for a player; the console has none to decide for.
            actions.forEach(action -> outcomes.put(action.getId(), ReplayOutcome.DENIED));
            return CompletableFuture.completedFuture(outcomes);
        }
        // One at a time in Ledger's order: each action sees what the one before it left on its cell,
        // and no two writes race a chunk's revision (a concurrent one would read as a conflict).
        var cancellation = new EditActorContext.Cancellation();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (ActionType action : actions) {
            chain = chain.thenCompose(unused -> onServer(() -> replayOne(action, context, cancellation)))
                    .handle((outcome, failure) -> {
                        outcomes.put(action.getId(), failure == null ? outcome : ReplayOutcome.FAILED);
                        return null;
                    });
        }
        return chain.thenApply(unused -> outcomes);
    }

    private CompletableFuture<ReplayOutcome> onServer(Supplier<CompletionStage<ReplayOutcome>> step) {
        return CompletableFuture.supplyAsync(step, server).thenCompose(stage -> stage);
    }

    // Server thread.
    private CompletionStage<ReplayOutcome> replayOne(ActionType action, ReplayContext context,
            EditActorContext.Cancellation cancellation) {
        Footprint footprint;
        try {
            footprint = footprint(action, context.mode());
        } catch (RuntimeException unreadable) {
            return CompletableFuture.completedFuture(ReplayOutcome.FAILED);
        }
        if (footprint == null) {
            return CompletableFuture.completedFuture(ReplayOutcome.UNSUPPORTED);
        }
        if (!context.authorized().getAsBoolean()) {
            return CompletableFuture.completedFuture(ReplayOutcome.DENIED);
        }
        ServerLevel level = action.getWorld() == null ? null
                : server.getLevel(ResourceKey.create(Registries.DIMENSION, action.getWorld()));
        if (level == null) {
            return CompletableFuture.completedFuture(ReplayOutcome.FAILED);
        }
        BlockVector3 pos = footprint.pos();
        // Loaded as Ledger's own replay would load it; the read and the write need the chunk.
        level.getChunk(pos.x() >> 4, pos.z() >> 4);
        if (!footprint.holds(access.read(level, footprint.kind(), pos.x(), pos.y(), pos.z(), ""))) {
            return CompletableFuture.completedFuture(ReplayOutcome.CONFLICT);
        }
        var operator = context.operator();
        UUID unit = UUID.randomUUID();
        var source = context.mode() == ReplayMode.ROLLBACK
                ? EditActorContext.Source.LEDGER_ROLLBACK : EditActorContext.Source.LEDGER_RESTORE;
        var original = action.getSourceProfile() == null ? null : action.getSourceProfile().id();
        var edit = new EditActorContext(operator.getUUID(), operator.getGameProfile().name(), source, unit,
                context.replayId(), original, level.dimension().identifier().toString(), SideEffectSet.defaults(),
                cancellation.child(), context.replayId());
        FabricRegionCommands.authorizeWith(unit, context.authorized());
        CompletionStage<CommitReceipt> written;
        try {
            written = FabricEdits.nativeService(server).submitNative(edit, footprint.kind(), pos, footprint.target(),
                    receipt -> { });
        } catch (RuntimeException refused) {
            FabricRegionCommands.forgetAuthority(unit);
            return CompletableFuture.completedFuture(classify(refused, context));
        }
        return written.handle((receipt, failure) -> {
            FabricRegionCommands.forgetAuthority(unit);
            return failure == null ? outcome(receipt) : classify(failure, context);
        });
    }

    private static ReplayOutcome outcome(CommitReceipt receipt) {
        if (!receipt.applied().isEmpty() || receipt.noOp() != 0 && receipt.denied() == 0) {
            return ReplayOutcome.APPLIED;
        }
        return switch (receipt.outcome()) {
            case CONFLICT -> ReplayOutcome.CONFLICT;
            case PARTIAL -> ReplayOutcome.DENIED;
            default -> receipt.denied() != 0 ? ReplayOutcome.DENIED : ReplayOutcome.FAILED;
        };
    }

    // A refusal for a block without a lifecycle contract is unsupported; one after the operator lost
    // the permission is denied; anything else failed and stays unmarked.
    private static ReplayOutcome classify(Throwable failure, ReplayContext context) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof UnsupportedBlockException) {
                return ReplayOutcome.UNSUPPORTED;
            }
            if (current instanceof FabricCommitCoordinator.CommitFailure commit && !commit.receipt().applied().isEmpty()) {
                return ReplayOutcome.APPLIED;
            }
        }
        return context.authorized().getAsBoolean() ? ReplayOutcome.FAILED : ReplayOutcome.DENIED;
    }

    // FAWE's own rows carry both full sides; Ledger's block rows carry states and one side's NBT.
    // Null for every other action type: no footprint is known, so it is refused.
    private Footprint footprint(ActionType action, ReplayMode mode) {
        boolean rollback = mode == ReplayMode.ROLLBACK;
        var pos = action.getPos();
        switch (action.getIdentifier()) {
            case FaweLedgerActions.BLOCK, FaweLedgerActions.BIOME -> {
                JsonObject extra = JsonParser.parseString(action.getExtraData()).getAsJsonObject();
                if (!LedgerPayloads.FORMAT.equals(extra.get("format").getAsString())) {
                    return null;
                }
                var kind = EditDelta.Kind.valueOf(extra.get("kind").getAsString());
                EditValue before = side(extra.getAsJsonObject("before"));
                EditValue after = side(extra.getAsJsonObject("after"));
                return new Footprint(kind, BlockVector3.at(pos.getX(), pos.getY(), pos.getZ()),
                        rollback ? after : before, rollback ? before : after, true);
            }
            case "block-change", "block-place", "block-break" -> {
                var block = (BlockChangeActionType) action;
                var blocks = server.registryAccess().lookupOrThrow(Registries.BLOCK);
                BlockState old = block.oldBlockState(blocks);
                BlockState now = block.newBlockState(blocks);
                // Native Ledger rows have no portable before/after values; they cannot restore a mark.
                if (com.fastasyncworldedit.fabric.edit.PositionDataRegistry.portable(old)
                        || com.fastasyncworldedit.fabric.edit.PositionDataRegistry.portable(now)) {
                    return null;
                }
                // Ledger keeps the NBT of the side its own replay loads: the old block for a rollback
                // of a break or change, the new block for a restore of a placement.
                boolean nbtOnTarget = rollback ? !action.getIdentifier().equals("block-place")
                        : action.getIdentifier().equals("block-place");
                BlockState target = rollback ? old : now;
                FrozenNbt nbt = nbtOnTarget && target.hasBlockEntity() && action.getExtraData() != null
                        ? FrozenNbt.capture(parse(action.getExtraData())) : null;
                return new Footprint(EditDelta.Kind.BLOCK, BlockVector3.at(pos.getX(), pos.getY(), pos.getZ()),
                        new EditValue(id(rollback ? now : old), null), new EditValue(id(target), nbt), false);
            }
            default -> {
                return null;
            }
        }
    }

    private static EditValue side(JsonObject side) {
        var nbt = side.get("nbt");
        return new EditValue(side.get("id").getAsString(),
                nbt == null || nbt.isJsonNull() ? null : FrozenNbt.capture(parse(nbt.getAsString())));
    }

    private static net.minecraft.nbt.CompoundTag parse(String snbt) {
        try {
            return TagParser.parseCompoundFully(snbt);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException invalid) {
            throw new IllegalArgumentException("Unreadable action NBT", invalid);
        }
    }

    private static String id(BlockState state) {
        return FabricAdapter.adapt(state).getAsString();
    }
}
