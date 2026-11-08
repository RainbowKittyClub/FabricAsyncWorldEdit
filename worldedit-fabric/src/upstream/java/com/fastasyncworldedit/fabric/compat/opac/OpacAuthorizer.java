package com.fastasyncworldedit.fabric.compat.opac;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.sk89q.worldedit.fabric.FabricAdapter;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import com.fastasyncworldedit.fabric.compat.EditIntegration;
import com.fastasyncworldedit.fabric.edit.CommitReceipt;
import com.fastasyncworldedit.fabric.edit.EditActorContext;
import com.fastasyncworldedit.fabric.edit.EditDelta;
import com.fastasyncworldedit.fabric.edit.EditValue;
import com.fastasyncworldedit.fabric.edit.PreparedChunkEdit;

/** Current-player OPAC checks for bounded commits; this provider never grants an operator bypass. */
public final class OpacAuthorizer implements EditIntegration {
    private static final Set<EditDelta.Kind> CAPABILITIES = Set.of(
            EditDelta.Kind.BLOCK, EditDelta.Kind.BIOME, EditDelta.Kind.POSITION_DATA);

    /** Loaded by EditIntegrations only when openpartiesandclaims is installed. */
    public OpacAuthorizer() {
    }

    @Override
    public String id() {
        return "openpartiesandclaims";
    }

    @Override
    public Set<EditDelta.Kind> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public CompletionStage<Reservation> prepare(PreparedChunkEdit proposal) {
        for (EditDelta delta : proposal.writes()) {
            if (!CAPABILITIES.contains(delta.kind())) {
                throw new UnsupportedOperationException("OPAC requires a verified footprint for " + delta.kind());
            }
        }
        List<ChunkPos> sensitiveReads = proposal.reads().stream()
                .filter(read -> read.kind() == EditDelta.Kind.POSITION_DATA
                        || read.kind() == EditDelta.Kind.ENTITY
                        || read.value() != null && read.value().nbt() != null)
                .map(read -> new ChunkPos(read.x() >> 4, read.z() >> 4)).distinct().toList();
        return CompletableFuture.completedFuture(new Checks(proposal.context(), sensitiveReads));
    }

    @Override
    public boolean authorizeSnapshot(ServerLevel level, ServerPlayer operator,
            EditActorContext context, int chunkX, int chunkZ) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("OPAC snapshot authorization requires the server thread");
        }
        if (context == null || operator == null || operator.hasDisconnected()
                || context.cancellation().isCancelled()
                || level.getServer().getPlayerList().getPlayer(context.actorId()) != operator
                || !level.dimension().identifier().toString().equals(context.dimension())) {
            return false;
        }
        return Objects.requireNonNull(OpenPACServerAPI.get(level.getServer()).getChunkProtection(),
                "OPAC protection unavailable").hasChunkAccess(operator,
                        level.dimension().identifier(), chunkX, chunkZ);
    }

    @Override
    public boolean authorizeDirect(ServerLevel level, ServerPlayer operator,
            EditActorContext context, EditDelta delta) {
        if (context == null) {
            return false;
        }
        return new Checks(context, List.of()).authorize(level, operator, context, delta);
    }

    // One change's answer within one tick. OPAC's edge check offsets along the hit direction, and
    // UP never leaves the chunk, so for these calls only chunk, player and block states matter.
    private record Change(EditDelta.Kind kind, int chunkX, int chunkZ, String before, String after,
            boolean beforeNbt, boolean afterNbt) { }

    // Answers kept for the current server tick only, so no live policy outlives the turn it was read in.
    private static final class Answers {
        private final java.util.HashMap<Change, Boolean> byChange = new java.util.HashMap<>();
        private int tick = -1;
    }

    // Only detached context and coordinates survive preparation; answers are cached per tick.
    private record Checks(EditActorContext expected, List<ChunkPos> sensitiveReads, Answers answers)
            implements Reservation {
        private Checks(EditActorContext expected, List<ChunkPos> sensitiveReads) {
            this(expected, sensitiveReads, new Answers());
        }

        @Override
        public boolean authorizeFootprint(ServerLevel level, ServerPlayer operator, EditActorContext context,
                EditDelta actualDelta) {
            return authorize(level, operator, context, actualDelta);
        }

        @Override
        public boolean authorize(ServerLevel level, ServerPlayer operator, EditActorContext context,
                EditDelta actualDelta) {
            if (actualDelta.kind() != EditDelta.Kind.BLOCK && actualDelta.kind() != EditDelta.Kind.BIOME) {
                return decide(level, operator, context, actualDelta);
            }
            int tick = level.getServer().getTickCount();
            if (answers.tick != tick) {
                answers.byChange.clear();
                answers.tick = tick;
            }
            var change = new Change(actualDelta.kind(), actualDelta.x() >> 4, actualDelta.z() >> 4,
                    actualDelta.before().id(), actualDelta.after().id(),
                    actualDelta.before().nbt() != null, actualDelta.after().nbt() != null);
            Boolean known = answers.byChange.get(change);
            if (known != null && !context.cancellation().isCancelled()) {
                return known;
            }
            boolean answer = decide(level, operator, context, actualDelta);
            answers.byChange.put(change, answer);
            return answer;
        }

        private boolean decide(ServerLevel level, ServerPlayer operator, EditActorContext context,
                EditDelta actualDelta) {
            if (!level.getServer().isSameThread()) {
                throw new IllegalStateException("OPAC authorization requires the server thread");
            }
            if (!expected.equals(context) || !level.dimension().identifier().toString().equals(context.dimension())) {
                throw new IllegalArgumentException("OPAC authorization context does not match its reservation");
            }
            ServerPlayer current = level.getServer().getPlayerList().getPlayer(context.actorId());
            if (current == null || current != operator || current.hasDisconnected()) {
                context.cancellation().cancel();
                return false;
            }
            if (context.cancellation().isCancelled()) {
                return false;
            }
            IChunkProtectionAPI protection = Objects.requireNonNull(
                    OpenPACServerAPI.get(level.getServer()).getChunkProtection(), "OPAC protection unavailable");
            for (ChunkPos chunk : sensitiveReads) {
                if (!protection.hasChunkAccess(current, level.dimension().identifier(), chunk.x(), chunk.z())) {
                    return false;
                }
            }
            BlockPos pos = new BlockPos(actualDelta.x(), actualDelta.y(), actualDelta.z());
            return switch (actualDelta.kind()) {
                case BLOCK -> block(protection, current, level, pos, actualDelta);
                case BIOME -> fullAccess(protection, current, level, pos);
                case POSITION_DATA -> fullAccess(protection, current, level, pos)
                        && !protection.onBlockInteraction(current, null, ItemStack.EMPTY,
                                level, pos, Direction.UP, false, false, true);
                case ENTITY -> throw new UnsupportedOperationException("Entity footprints are not supported");
            };
        }

        @Override
        public void beforePublication(ServerLevel level, List<EditDelta> authorized) {
        }

        @Override
        public CompletionStage<Void> reconcile(ServerLevel level, CommitReceipt receipt) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> record(CommitReceipt receipt) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close(ServerLevel level) {
        }

        @Override
        public void discard() {
        }
    }

    // Action methods return denial, whereas hasChunkAccess returns permission.
    private static boolean block(IChunkProtectionAPI protection, ServerPlayer actor, ServerLevel level,
            BlockPos pos, EditDelta delta) {
        BlockState before = state(delta.before());
        BlockState after = state(delta.after());
        if (before == after) {
            return fullAccess(protection, actor, level, pos)
                    && !protection.onBlockInteraction(actor, null, ItemStack.EMPTY,
                            level, pos, Direction.UP, false, false, true);
        }
        if ((delta.before().nbt() != null || delta.after().nbt() != null)
                && !fullAccess(protection, actor, level, pos)) {
            return false;
        }
        boolean removal = before.isAir() || !protection.onBlockInteraction(actor, null, ItemStack.EMPTY,
                level, pos, Direction.UP, true, false, true);
        boolean placement = after.isAir() || !protection.onEntityPlaceBlock(after, actor, level, pos);
        return removal && placement;
    }

    // Use the full proposed state so OPAC's placement exceptions apply to the placed block.
    private static BlockState state(EditValue value) {
        var parsed = com.sk89q.worldedit.world.block.BlockState.get(value.id());
        if (!parsed.getAsString().equals(value.id())) {
            throw new IllegalArgumentException("Noncanonical OPAC block state: " + value.id());
        }
        return FabricAdapter.adapt(parsed);
    }

    // The entity overload preserves current admin, nonally and impersonation modes.
    private static boolean fullAccess(IChunkProtectionAPI protection, ServerPlayer actor,
            ServerLevel level, BlockPos pos) {
        return protection.hasChunkAccess(actor, level.dimension().identifier(), pos.getX() >> 4, pos.getZ() >> 4);
    }
}
