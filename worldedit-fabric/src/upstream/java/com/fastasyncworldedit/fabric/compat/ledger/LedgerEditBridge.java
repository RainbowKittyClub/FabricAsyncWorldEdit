package com.fastasyncworldedit.fabric.compat.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import com.fastasyncworldedit.fabric.compat.EditIntegration;
import com.fastasyncworldedit.fabric.edit.CommitReceipt;
import com.fastasyncworldedit.fabric.edit.EditActorContext;
import com.fastasyncworldedit.fabric.edit.EditDelta;
import com.fastasyncworldedit.fabric.edit.FabricWorldAccess;
import com.fastasyncworldedit.fabric.edit.PreparedChunkEdit;
import com.github.quiltservertools.ledger.api.BatchAck;
import com.github.quiltservertools.ledger.api.LedgerApi;
import com.github.quiltservertools.ledger.api.ProducerBatch;
import com.github.quiltservertools.ledger.api.ProducerBatchException;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import org.apache.logging.log4j.Logger;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Durable Ledger audit for coordinated edits. Before publication every row is validated and the
 * intent journaled in the world's outbox; after it the receipt is journaled and the applied deltas
 * go to Ledger's acknowledged producer batch API, keyed by (fawe, operation, sequence). Restart
 * recovery ({@link LedgerRecovery}) finishes what a stop or crash left. Audit is required: a Ledger
 * without the producer API, a failed outbox write or unfinished recovery refuses the edit.
 */
public final class LedgerEditBridge implements EditIntegration {
    static final int REQUIRED_API_VERSION = 1;
    static final int REQUIRED_REPLAY_VERSION = 1;
    private static final Logger LOGGER = LogManagerCompat.getLogger();
    private static final long FIRST_RETRY_MS = 250;
    private static final long MAX_RETRY_MS = 5_000;

    // Null when the installed Ledger supports the producer batch contract.
    private final String incompatibility;
    private LedgerOutbox outbox;
    private LedgerReplayGuard replayGuard;
    private volatile boolean stopping;
    // Null once recovery finished; otherwise why proposals are refused.
    private volatile String notReady = "the Ledger audit bridge has not started";

    public LedgerEditBridge() {
        this.incompatibility = incompatibility();
        if (this.incompatibility == null) {
            FaweLedgerActions.register();
        }
    }

    @Override
    public String id() {
        return "ledger";
    }

    @Override
    public Set<EditDelta.Kind> capabilities() {
        return Set.of(EditDelta.Kind.BLOCK, EditDelta.Kind.BIOME);
    }

    @Override
    public void start(MinecraftServer server, FabricWorldAccess access) {
        if (incompatibility != null) {
            return;
        }
        // Every Ledger rollback and restore then changes the world through the coordinator.
        if (replayApi()) {
            replayGuard = new LedgerReplayGuard(server, access);
            LedgerApi.get().registerReplayGuard(replayGuard);
        } else {
            LOGGER.warn("The installed Ledger has no replay API; its rollbacks bypass FAWE's protection checks");
        }
        outbox = new LedgerOutbox(server.getWorldPath(LevelResource.ROOT).resolve("fawe").resolve("ledger-outbox"));
        notReady = "Ledger audit recovery is still running";
        new LedgerRecovery(server, access, outbox, batch -> append(batch, FIRST_RETRY_MS), () -> stopping).run()
                .whenComplete((unused, failure) -> {
                    if (failure == null) {
                        notReady = null;
                    } else {
                        notReady = "Ledger audit recovery failed: " + failure.getMessage();
                        LOGGER.warn("{}; audited edits stay refused until restart", notReady, failure);
                    }
                });
    }

    @Override
    public void stop() {
        stopping = true;
        if (replayGuard != null) {
            LedgerApi.get().unregisterReplayGuard(replayGuard);
        }
    }

    @Override
    public CompletionStage<Reservation> prepare(PreparedChunkEdit proposal) {
        if (incompatibility != null) {
            return refuse("Ledger audit is required but " + incompatibility);
        }
        var source = proposal.context().source();
        if (source == EditActorContext.Source.LEDGER_ROLLBACK || source == EditActorContext.Source.LEDGER_RESTORE) {
            // Ledger flags the original rows and writes one audit row for the replay; rows for its
            // writes would duplicate the originals. Scoped to this unit, not to other edits.
            return CompletableFuture.completedFuture(new Unrecorded());
        }
        String waiting = notReady;
        if (waiting != null) {
            return refuse(waiting);
        }
        LedgerApi api = LedgerApi.get();
        List<EditDelta> changes = proposal.writes().stream().filter(delta -> !delta.isNoOp()).toList();
        if (changes.size() > api.producerBatchLimit()) {
            return refuse("Ledger records at most " + api.producerBatchLimit() + " changes per chunk edit, this one has "
                    + changes.size());
        }
        LedgerPayloads.Origin origin = LedgerPayloads.Origin.of(proposal.context());
        Instant probe = Instant.now();
        for (EditDelta delta : changes) {
            // The longest sequence, so a row that fits now still fits whatever sequence it gets.
            String refusal = api.validateProducerAction(LedgerPayloads.action(origin, Long.MAX_VALUE, delta, probe));
            if (refusal != null) {
                return refuse("Ledger would not record the " + delta.kind().name().toLowerCase() + " change at "
                        + delta.x() + "," + delta.y() + "," + delta.z() + ": " + refusal);
            }
        }
        var reservation = new LedgerReservation(UUID.randomUUID(), origin, changes);
        if (changes.isEmpty()) {
            return CompletableFuture.completedFuture(reservation);
        }
        return outbox.writeIntent(new LedgerOutbox.Unit(reservation.id, origin, proposal.chunkX(), proposal.chunkZ(),
                changes, null)).handle((unused, failure) -> failure == null
                        ? CompletableFuture.<Reservation>completedFuture(reservation)
                        : refuse(rootMessage(failure))).thenCompose(stage -> stage);
    }

    private static CompletableFuture<Reservation> refuse(String reason) {
        return CompletableFuture.failedFuture(new IllegalStateException(reason + "; no writes were made"));
    }

    private static boolean replayApi() {
        try {
            return LedgerApi.class.getField("REPLAY_API_VERSION").getInt(null) >= REQUIRED_REPLAY_VERSION;
        } catch (ReflectiveOperationException | LinkageError missing) {
            return false;
        }
    }

    /** A replay unit: authorizes everything here (claims and permission are other checks) and records nothing. */
    private static final class Unrecorded implements Reservation {
        @Override
        public boolean authorize(ServerLevel level, ServerPlayer operator, EditActorContext context, EditDelta delta) {
            return true;
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

    private static String incompatibility() {
        try {
            int version = LedgerApi.class.getField("PRODUCER_API_VERSION").getInt(null);
            return version >= REQUIRED_API_VERSION ? null
                    : "the installed Ledger has producer API " + version + ", not " + REQUIRED_API_VERSION;
        } catch (ReflectiveOperationException | LinkageError missing) {
            return "the installed Ledger has no producer batch API";
        }
    }

    private static String key(EditDelta delta) {
        return delta.kind() + "/" + delta.x() + "/" + delta.y() + "/" + delta.z() + "/" + delta.key();
    }

    private final class LedgerReservation implements Reservation {
        private final UUID id;
        private final LedgerPayloads.Origin origin;
        private final List<EditDelta> intent;
        private final Map<String, Integer> index = new HashMap<>();

        private LedgerReservation(UUID id, LedgerPayloads.Origin origin, List<EditDelta> intent) {
            this.id = id;
            this.origin = origin;
            this.intent = intent;
            for (int position = 0; position < intent.size(); position++) {
                index.put(key(intent.get(position)), position);
            }
        }

        // Revalidation: publish only deltas the journaled intent holds exactly. A changed delta is a
        // conflict upstream, retried with a fresh proposal and so a fresh, matching intent.
        @Override
        public boolean authorize(ServerLevel level, ServerPlayer operator, EditActorContext context,
                EditDelta actualDelta) {
            Integer position = index.get(key(actualDelta));
            return position != null && intent.get(position).equals(actualDelta);
        }

        @Override
        public CompletionStage<Void> reconcile(ServerLevel level, CommitReceipt receipt) {
            return CompletableFuture.completedFuture(null);
        }

        // Only actual applied deltas become rows; no-op and denied proposals produce none.
        @Override
        public CompletionStage<Void> record(CommitReceipt receipt) {
            if (receipt.applied().isEmpty()) {
                return intent.isEmpty() ? CompletableFuture.completedFuture(null) : outbox.remove(id);
            }
            Instant timestamp = Instant.now();
            List<LedgerOutbox.Applied> applied = new ArrayList<>(receipt.applied().size());
            List<ProducerBatch.Entry> entries = new ArrayList<>(receipt.applied().size());
            for (CommitReceipt.AppliedDelta delta : receipt.applied()) {
                applied.add(new LedgerOutbox.Applied(delta.sequence(), index.get(key(delta.delta())),
                        delta.delta().after()));
                entries.add(new ProducerBatch.Entry(delta.sequence(),
                        LedgerPayloads.action(origin, delta.sequence(), delta.delta(), timestamp)));
            }
            ProducerBatch batch = new ProducerBatch(LedgerPayloads.SOURCE, origin.operation(), entries);
            // A receipt that cannot be journaled does not hold the rows back; recovery then has
            // only the intent and quarantines the unit if the server dies before Ledger acks.
            return outbox.writeReceipt(id, new LedgerOutbox.Receipt(timestamp, applied))
                    .exceptionally(failure -> {
                        LOGGER.warn("Ledger audit outbox: {}", rootMessage(failure));
                        return null;
                    })
                    .thenCompose(unused -> append(batch, FIRST_RETRY_MS))
                    .handle((ack, failure) -> {
                        if (failure == null) {
                            return outbox.remove(id);
                        }
                        if (stopping) {
                            return CompletableFuture.<Void>failedFuture(new IllegalStateException(
                                    "Ledger rows deferred to restart recovery: " + rootMessage(failure)));
                        }
                        return outbox.quarantine(id, "Ledger refused the rows: " + rootMessage(failure))
                                .thenCompose(unused -> CompletableFuture.<Void>failedFuture(failure));
                    }).thenCompose(stage -> stage);
        }

        @Override
        public void close(ServerLevel level) {
        }

        @Override
        public void discard() {
            if (!intent.isEmpty()) {
                outbox.remove(id);
            }
        }
    }

    // Transient refusals retry with backoff while the server runs; the unit keeps its admission
    // slot, so FAWE admits no new work while Ledger is behind. Conflicts and invalid rows fail at once.
    private CompletionStage<BatchAck> append(ProducerBatch batch, long delayMs) {
        return LedgerApi.get().appendBatch(batch).handle((ack, failure) -> {
            if (failure == null) {
                return CompletableFuture.completedFuture(ack);
            }
            ProducerBatchException refused = refusal(failure);
            if (!stopping && refused != null && (refused.reason() == ProducerBatchException.Reason.BACKPRESSURE
                    || refused.reason() == ProducerBatchException.Reason.UNAVAILABLE
                    || refused.reason() == ProducerBatchException.Reason.STOPPED)) {
                var later = CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS);
                return CompletableFuture.supplyAsync(() -> null, later)
                        .thenCompose(ignored -> append(batch, Math.min(delayMs * 2, MAX_RETRY_MS)));
            }
            return CompletableFuture.<BatchAck>failedFuture(failure);
        }).thenCompose(stage -> stage);
    }

    private static ProducerBatchException refusal(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ProducerBatchException refused) return refused;
        }
        return null;
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }
}
