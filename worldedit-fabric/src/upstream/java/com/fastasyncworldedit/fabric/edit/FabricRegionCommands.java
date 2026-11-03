package com.fastasyncworldedit.fabric.edit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.history.changeset.ArrayListHistory;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BlockState;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Opt-in production commands for bounded cuboid edits with memory history and no neighbor callbacks. */
public final class FabricRegionCommands {
    private static final Map<UUID, String> permissions = new ConcurrentHashMap<>();
    private static long completed;

    private FabricRegionCommands() { }

    /** Register a separate explicit mode without changing ordinary WNA commands. */
    public static void initialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            if (!Boolean.getBoolean("fawe.experimental.coordinated")) return;
            dispatcher.register(Commands.literal("fawe-coordinated")
                    .then(Commands.literal("set").then(Commands.argument("state", StringArgumentType.word())
                            .executes(command -> submit(command.getSource(), null, StringArgumentType.getString(command, "state")))))
                    .then(Commands.literal("replace").then(Commands.argument("before", StringArgumentType.word())
                            .then(Commands.argument("state", StringArgumentType.word()).executes(command -> submit(
                                    command.getSource(), StringArgumentType.getString(command, "before"),
                                    StringArgumentType.getString(command, "state")))))));
        });
    }

    /** Recheck the command's current permission alongside integration authorization for every unit. */
    public static boolean authorized(EditActorContext context, ServerPlayer player) {
        if (context.source() != EditActorContext.Source.PLAYER) return true;
        String node = permissions.get(context.operationId());
        return node != null && FabricAdapter.adaptPlayer(player).hasPermission(node);
    }

    /** Owner-thread count of fully completed production commands, not submission acknowledgements. */
    public static long completed() { return completed; }

    // The command thread only captures immutable geometry and state identifiers for preparation.
    private static int submit(CommandSourceStack source, String matching, String replacement) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            var actor = FabricAdapter.adaptPlayer(player);
            String node = matching == null ? "worldedit.region.set" : "worldedit.region.replace";
            if (!actor.hasPermission(node)) throw new UnsupportedOperationException("Missing permission: " + node);
            var session = WorldEdit.getInstance().getSessionManager().get(actor);
            var world = actor.getWorld();
            var selection = session.getSelection(world);
            if (!(selection instanceof CuboidRegion) || session.getMask() != null || session.getSourceMask() != null
                    || session.getTransform() != null) {
                throw new UnsupportedOperationException("Coordinated commands require a cuboid without session masks or transforms");
            }
            var limit = actor.getLimit();
            long volume = selection.getVolume();
            if (volume > limit.MAX_CHANGES.get() || volume > limit.MAX_CHECKS.get()
                    || (session.getBlockChangeLimit() >= 0 && volume > session.getBlockChangeLimit())
                    || limit.INVENTORY_MODE != 0 || limit.MAX_HISTORY < 64
                    || (limit.DISALLOWED_BLOCKS != null && !limit.DISALLOWED_BLOCKS.isEmpty())
                    || (limit.REMAP_PROPERTIES != null && !limit.REMAP_PROPERTIES.isEmpty())
                    || (limit.STRIP_NBT != null && !limit.STRIP_NBT.isEmpty())) {
                throw new UnsupportedOperationException("This limit/inventory policy has no coordinated command adapter");
            }
            var parser = new com.sk89q.worldedit.extension.input.ParserContext();
            parser.setActor(actor);
            parser.setWorld(world);
            parser.setSession(session);
            parser.setRestricted(true);
            var parsed = WorldEdit.getInstance().getBlockFactory().parseFromInput(replacement, parser);
            if (parsed.hasNbtData()) throw new UnsupportedOperationException("Command NBT requires an explicit API payload");
            String state = parsed.toImmutableState().getAsString();
            String before = matching == null ? null : WorldEdit.getInstance().getBlockFactory()
                    .parseFromInput(matching, parser).toImmutableState().getAsString();
            var min = selection.getMinimumPoint();
            var max = selection.getMaximumPoint();
            long units = (long) ((max.x() >> 4) - (min.x() >> 4) + 1)
                    * ((max.z() >> 4) - (min.z() >> 4) + 1) * ((max.y() >> 4) - (min.y() >> 4) + 1);
            if (units > 64) throw new IllegalArgumentException("Coordinated command is limited to 64 sections");
            var service = FabricEdits.get(source.getServer());
            var history = new ArrayListHistory();
            UUID operationId = UUID.randomUUID();
            var context = new EditActorContext(player.getUUID(), actor.getName(), EditActorContext.Source.PLAYER,
                    operationId, null, null, player.level().dimension().identifier().toString(), SideEffectSet.none(),
                    new EditActorContext.Cancellation());
            // Sequential sections keep overlapping snapshots fresh; each section can independently reprepare.
            CompletableFuture<List<CommitReceipt>> chain = CompletableFuture.completedFuture(new ArrayList<>());
            for (int cx = min.x() >> 4; cx <= max.x() >> 4; cx++) {
                for (int cz = min.z() >> 4; cz <= max.z() >> 4; cz++) {
                    for (int sy = min.y() >> 4; sy <= max.y() >> 4; sy++) {
                        int x0 = Math.max(min.x(), cx << 4), x1 = Math.min(max.x(), (cx << 4) + 15);
                        int z0 = Math.max(min.z(), cz << 4), z1 = Math.min(max.z(), (cz << 4) + 15);
                        int y0 = Math.max(min.y(), sy << 4), y1 = Math.min(max.y(), (sy << 4) + 15);
                        BlockVector2 chunk = BlockVector2.at(cx, cz);
                        var unitContext = new EditActorContext(context.actorId(), context.actorName(), context.source(),
                                UUID.randomUUID(), operationId, null, context.dimension(), context.sideEffects(), context.cancellation());
                        chain = chain.thenCompose(receipts -> {
                            permissions.put(unitContext.operationId(), node);
                            try {
                                return service.submitRetrying(unitContext, chunk, history, queue -> {
                            BlockState block = BlockState.get(state);
                            for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
                                if (before == null || queue.getBlock(x, y, z).getAsString().equals(before)) queue.setBlock(x, y, z, block);
                            }
                        }).thenApply(next -> {
                            receipts.addAll(next);
                            if (next.stream().anyMatch(receipt -> receipt.outcome() != CommitReceipt.Outcome.COMPLETE)) {
                                throw new IllegalStateException("Coordinated command stopped after partial, denied or conflicting work");
                            }
                            return receipts;
                        }).whenComplete((value, failure) -> permissions.remove(unitContext.operationId()));
                            } catch (Throwable failure) {
                                permissions.remove(unitContext.operationId());
                                return CompletableFuture.<List<CommitReceipt>>failedFuture(failure);
                            }
                        }).toCompletableFuture();
                    }
                }
            }
            chain.whenComplete((receipts, failure) -> source.getServer().execute(() -> {
                permissions.remove(operationId);
                session.remember(actor, world, history, limit);
                if (failure == null) {
                    completed++;
                    source.sendSuccess(() -> Component.literal("Coordinated edit complete: " + history.size()
                            + " changes (memory history; no neighbor callbacks)."), false);
                } else {
                    // A refusal is clean only when no unit recorded or may have made changes.
                    CommitReceipt unknown = receiptMatching(failure,
                            receipt -> receipt.outcome() == CommitReceipt.Outcome.APPLIED_UNKNOWN);
                    CommitReceipt refusal = history.size() == 0 && unknown == null
                            ? receiptMatching(failure, FabricRegionCommands::isPreWriteRefusal) : null;
                    if (refusal != null) {
                        source.sendFailure(Component.literal("Coordinated edit REFUSED for chunk "
                                + refusal.chunkX() + "," + refusal.chunkZ()
                                + "; no changes were written: " + refusalReason(refusal)));
                    } else if (unknown != null) {
                        source.sendFailure(Component.literal("Coordinated edit failed: chunk "
                                + unknown.chunkX() + "," + unknown.chunkZ() + " was written but could not be"
                                + " read back, so it is quarantined; at least " + history.size()
                                + " recorded changes: " + failure));
                    } else {
                        source.sendFailure(Component.literal("Coordinated edit failed after " + history.size()
                                + " recorded changes: " + failure));
                    }
                }
            }));
            source.sendSuccess(() -> Component.literal("Coordinated edit admitted: " + operationId), false);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("Coordinated edit refused: " + failure.getMessage()));
            return 0;
        }
    }

    // First receipt carried by the failure chain that matches; partial/post-write failures keep their wording.
    private static CommitReceipt receiptMatching(Throwable failure, Predicate<CommitReceipt> test) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof FabricCommitCoordinator.CommitFailure commit) {
                CommitReceipt receipt = commit.receipt();
                if (test.test(receipt)) return receipt;
            }
            if (current instanceof FabricEdits.OperationFailure operation) {
                for (CommitReceipt receipt : operation.publishedReceipts()) {
                    if (test.test(receipt)) return receipt;
                }
            }
        }
        return null;
    }

    private static boolean isPreWriteRefusal(CommitReceipt receipt) {
        return receipt.outcome() == CommitReceipt.Outcome.FAILED_BEFORE_WRITE && receipt.applied().isEmpty();
    }

    // Keep the integration's chunk/position and reason, dropping exception/wrapper prefixes.
    private static String refusalReason(CommitReceipt receipt) {
        String text = receipt.failure();
        int separator = text.indexOf(": ");
        if (separator > 0 && text.substring(0, separator).chars().noneMatch(Character::isWhitespace)) {
            text = text.substring(separator + 2);
        }
        String wrapper = FabricCommitCoordinator.INTEGRATION_REFUSAL_PREFIX;
        return text.startsWith(wrapper) ? text.substring(wrapper.length()) : text;
    }
}
