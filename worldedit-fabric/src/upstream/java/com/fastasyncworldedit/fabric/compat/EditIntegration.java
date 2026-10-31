package com.fastasyncworldedit.fabric.compat;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.fastasyncworldedit.fabric.edit.CommitReceipt;
import com.fastasyncworldedit.fabric.edit.EditActorContext;
import com.fastasyncworldedit.fabric.edit.EditDelta;
import com.fastasyncworldedit.fabric.edit.PreparedChunkEdit;

/** Version-two bridge contract; installed-but-unregistered integrations refuse coordinated edits. */
public interface EditIntegration {

    /** Stable provider identifier used by the required-integration policy. */
    public String id();

    /** Payload families supported by this exact provider version. */
    public Set<EditDelta.Kind> capabilities();

    /**
     * Reserve bounded resources and validate detached payloads off the owner thread.
     * Audit providers may persist intent here. Null stages/reservations are invalid.
     * Preparation must not acquire live-world resources. It can finish after shutdown;
     * a late result is discarded off-thread rather than dispatched to a stopped server.
     */
    public CompletionStage<Reservation> prepare(PreparedChunkEdit proposal);

    /** Authorize a full chunk snapshot before any detached callback can observe its NBT. */
    public default boolean authorizeSnapshot(ServerLevel level, ServerPlayer operator,
            EditActorContext context, int chunkX, int chunkZ) {
        return true;
    }

    /** Owner-thread direct WNA authorization; providers must explicitly support this route. */
    public default boolean authorizeDirect(ServerLevel level, ServerPlayer operator,
            EditActorContext context, EditDelta delta) {
        throw new UnsupportedOperationException("Provider does not support direct WNA: " + id());
    }

    /** Per-unit bridge state, retained through reconciliation and durable recording. */
    public interface Reservation {

        /**
         * Check current policy on the owner thread immediately before the bounded write.
         * The current player may be null only for explicitly supported administrative policy.
         * No blocking, asynchronous work or permission caching across calls is permitted here.
         */
        public boolean authorize(ServerLevel level, ServerPlayer operator, EditActorContext context,
                EditDelta actualDelta);

        /**
         * Owner-thread prepublication scope, including deferred watcher publication if needed.
         * Providers that only care about the route-aware hook may leave this empty.
         */
        public default void beforePublication(ServerLevel level, List<EditDelta> authorized) {
        }

        /**
         * Owner-thread veto for whole-section publication, after authorization and before
         * {@link #beforePublication(ServerLevel, List, boolean)}. Returning false forces the
         * ordinary per-cell route for every unit in this proposal. Implementations must not
         * mutate the world or acquire a live-world token here.
         */
        public default boolean allowsDirectSections(ServerLevel level, List<EditDelta> authorized) {
            return true;
        }

        /**
         * Route-aware prepublication scope. {@code directSections} is true only when every
         * authorized delta will be published by replacing whole sections and no ordinary cell
         * write will occur in this proposal. Providers that only need the legacy hook keep
         * their existing two-argument implementation.
         */
        public default void beforePublication(ServerLevel level, List<EditDelta> authorized,
                boolean directSections) {
            beforePublication(level, authorized);
        }

        /**
         * Reconcile actual effects on the owner thread, including partial failures.
         * Complete server metadata here; vanilla light completion and recipient sends follow.
         * New watchers must remain deferred until publish is called.
         */
        public CompletionStage<Void> reconcile(ServerLevel level, CommitReceipt receipt);

        /** Owner-thread watcher publication after vanilla light completion and per-recipient chunk sends. */
        public default CompletionStage<Void> publish(ServerLevel level, CommitReceipt receipt) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        /** Off-thread awaited recording; acknowledgement means durable completion, not enqueue. */
        public CompletionStage<Void> record(CommitReceipt receipt);

        /** Owner-thread exactly-once release; close or discard is called, never both. */
        public void close(ServerLevel level);

        /**
         * Release detached preparation that cannot be handed to a live world, on any thread.
         * Must not access server state or require another owner-thread turn. In-flight recorders
         * retain their own immutable input/resources after either release method returns.
         */
        public void discard();
    }
}
