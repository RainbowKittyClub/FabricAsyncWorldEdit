package com.fastasyncworldedit.fabric.compat.polymer;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.sk89q.worldedit.fabric.FabricAdapter;

import eu.pb4.polymer.common.api.PolymerBulkEdits;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

import com.fastasyncworldedit.fabric.compat.EditIntegration;
import com.fastasyncworldedit.fabric.edit.CommitReceipt;
import com.fastasyncworldedit.fabric.edit.EditActorContext;
import com.fastasyncworldedit.fabric.edit.EditDelta;
import com.fastasyncworldedit.fabric.edit.FrozenNbt;
import com.fastasyncworldedit.fabric.edit.PreparedChunkEdit;

/**
 * Consumes Polymer's public bulk-edit API; no Polymer private state is reflected.
 *
 * <p>Preparation remains detached. A loaded-chunk token is acquired on the owner thread in
 * {@link Reservation#beforePublication(ServerLevel, List, boolean)} only when the FAWE route is
 * whole-section replacement, reconciled after the non-yielding write, published after vanilla
 * light completion and recipient sends, and closed on the owner thread exactly once. Non-block
 * payloads are passed through without a token.</p>
 */
public final class PolymerEditBridge implements EditIntegration {
    private static final Set<EditDelta.Kind> CAPABILITIES = Set.of(EditDelta.Kind.values());
    private static final int MAX_SECTIONS = 16;

    /** Loaded by EditIntegrations only when polymer-core is installed. */
    public PolymerEditBridge() {
    }

    @Override
    public String id() {
        return "polymer-core";
    }

    @Override
    public Set<EditDelta.Kind> capabilities() {
        // Polymer observes BLOCK; other families are transparent and must not block publication.
        return CAPABILITIES;
    }

    @Override
    public CompletionStage<Reservation> prepare(PreparedChunkEdit proposal) {
        return CompletableFuture.completedFuture(new PolymerReservation(proposal));
    }

    private static final class PolymerReservation implements Reservation {
        private final PreparedChunkEdit proposal;
        private ServerLevel level;
        private PolymerBulkEdits.Token token;
        private boolean reconciled;
        private boolean closed;

        private PolymerReservation(PreparedChunkEdit proposal) {
            this.proposal = proposal;
        }

        @Override
        public boolean authorize(ServerLevel level, ServerPlayer operator, EditActorContext context,
                EditDelta actualDelta) {
            return true;
        }

        @Override
        public boolean allowsDirectSections(ServerLevel level, List<EditDelta> authorized) {
            owner(level);
            if (authorized.isEmpty()) return false;
            Set<Integer> sections = new HashSet<>();
            for (EditDelta delta : authorized) {
                if (delta.kind() != EditDelta.Kind.BLOCK) return false;
                sections.add(delta.y() >> 4);
                if (sections.size() > MAX_SECTIONS) return false;
            }
            return true;
        }

        @Override
        public void beforePublication(ServerLevel level, List<EditDelta> authorized,
                boolean directSections) {
            owner(level);
            if (!directSections) return;
            if (this.token != null) {
                throw new IllegalStateException("Polymer token was already prepared");
            }
            Set<Integer> sections = new LinkedHashSet<>();
            Map<BlockPos, net.minecraft.world.level.block.state.BlockState> proposed =
                    new LinkedHashMap<>();
            for (EditDelta delta : authorized) {
                if (delta.kind() != EditDelta.Kind.BLOCK) {
                    throw new IllegalStateException("Direct-section route received " + delta.kind());
                }
                sections.add(delta.y() >> 4);
                proposed.put(new BlockPos(delta.x(), delta.y(), delta.z()), FabricAdapter.adapt(
                        com.sk89q.worldedit.world.block.BlockState.get(delta.after().id())));
            }
            if (sections.isEmpty()) return;
            if (sections.size() > MAX_SECTIONS) {
                throw new IllegalStateException("Polymer supports at most " + MAX_SECTIONS + " sections");
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(proposal.chunkX(), proposal.chunkZ());
            if (chunk == null || chunk.getPos().x() != proposal.chunkX()
                    || chunk.getPos().z() != proposal.chunkZ()) {
                throw new IllegalStateException("Required chunk is not the current loaded instance");
            }
            this.level = level;
            try {
                this.token = PolymerBulkEdits.prepare(chunk, Set.copyOf(sections), Map.copyOf(proposed));
            } catch (UnsupportedOperationException refused) {
                throw new IllegalStateException("Polymer direct-section route refused for chunk "
                        + proposal.chunkX() + "," + proposal.chunkZ()
                        + "; the chunk has an unsupported attachment or Immersive Portals and no writes "
                        + "were made: " + refused.getMessage(), refused);
            } catch (IllegalStateException refused) {
                throw new IllegalStateException("Polymer direct-section route refused for chunk "
                        + proposal.chunkX() + "," + proposal.chunkZ()
                        + "; the chunk is reserved/quarantined or no longer loaded, no writes were made, "
                        + "and unload/reload may be required: " + refused.getMessage(), refused);
            }
        }

        @Override
        public CompletionStage<Void> reconcile(ServerLevel level, CommitReceipt receipt) {
            owner(level);
            if (this.token == null) return CompletableFuture.completedFuture(null);
            if (level != this.level) {
                throw new IllegalStateException("Polymer reconciliation crossed dimensions");
            }
            verify(receipt, this.token.reconcile());
            this.reconciled = true;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> publish(ServerLevel level, CommitReceipt receipt) {
            owner(level);
            if (this.token == null) return CompletableFuture.completedFuture(null);
            if (level != this.level) {
                throw new IllegalStateException("Polymer publication crossed dimensions");
            }
            if (!this.reconciled) {
                throw new IllegalStateException("Polymer token published before reconciliation");
            }
            this.token.publish();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> record(CommitReceipt receipt) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close(ServerLevel level) {
            owner(level);
            if (this.closed) return;
            this.closed = true;
            PolymerBulkEdits.Token closing = this.token;
            this.token = null;
            if (closing != null) {
                closing.close();
            }
        }

        @Override
        public void discard() {
            if (this.token != null) {
                throw new IllegalStateException("A live Polymer token must be closed on the owner thread");
            }
            this.level = null;
            this.reconciled = false;
        }

        private void owner(ServerLevel level) {
            if (!level.getServer().isSameThread()) {
                throw new IllegalStateException("Polymer bulk edits require the server thread");
            }
        }
    }

    /**
     * Polymer independently observes the final section state/NBT. Every observed change must match
     * an applied FAWE receipt by position and payload, and every applied block delta must be
     * observed. Disagreement is a finalization failure; no write is retried.
     */
    private static void verify(CommitReceipt receipt, List<PolymerBulkEdits.Change> changes) {
        Map<BlockPos, EditDelta> applied = new LinkedHashMap<>();
        for (CommitReceipt.AppliedDelta entry : receipt.applied()) {
            EditDelta delta = entry.delta();
            if (delta.kind() != EditDelta.Kind.BLOCK) {
                throw new IllegalStateException("Polymer receipt contains " + delta.kind());
            }
            BlockPos pos = new BlockPos(delta.x(), delta.y(), delta.z());
            if (applied.put(pos, delta) != null) {
                throw new IllegalStateException("Duplicate applied block delta at " + pos);
            }
        }
        for (PolymerBulkEdits.Change change : changes) {
            EditDelta delta = applied.remove(change.pos());
            if (delta == null) {
                throw new IllegalStateException("Polymer observed an unrecorded change at " + change.pos());
            }
            if (!FabricAdapter.adapt(change.before()).getAsString().equals(delta.before().id())
                    || !FabricAdapter.adapt(change.after()).getAsString().equals(delta.after().id())) {
                throw new IllegalStateException("Polymer state disagreement at " + change.pos());
            }
            if (!Objects.equals(frozen(change.beforeNbt()), delta.before().nbt())
                    || !Objects.equals(frozen(change.afterNbt()), delta.after().nbt())) {
                throw new IllegalStateException("Polymer NBT disagreement at " + change.pos());
            }
        }
        if (!applied.isEmpty()) {
            throw new IllegalStateException("Polymer did not observe applied changes at " + applied.keySet());
        }
    }

    private static FrozenNbt frozen(CompoundTag tag) {
        if (tag == null) return null;
        CompoundTag copy = tag.copy();
        // Polymer's saving snapshot adds this serialization marker; FAWE's full metadata does not.
        copy.remove("keepPacked");
        return FrozenNbt.capture(copy);
    }
}
