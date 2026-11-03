package com.fastasyncworldedit.fabric.compat.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import com.fastasyncworldedit.fabric.compat.EditIntegration;
import com.fastasyncworldedit.fabric.edit.CommitReceipt;
import com.fastasyncworldedit.fabric.edit.EditActorContext;
import com.fastasyncworldedit.fabric.edit.EditDelta;
import com.fastasyncworldedit.fabric.edit.PreparedChunkEdit;
import com.github.quiltservertools.ledger.api.BatchAck;
import com.github.quiltservertools.ledger.api.LedgerApi;
import com.github.quiltservertools.ledger.api.ProducerBatch;
import com.github.quiltservertools.ledger.api.ProducerBatchException;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Durable Ledger audit for coordinated edits. Every row a proposal would write is validated before
 * publication; after publication the actual applied deltas go to Ledger's acknowledged producer
 * batch API, keyed by (fawe, operation, sequence). Audit is required: an installed Ledger without
 * that API refuses every coordinated edit rather than letting one go unrecorded.
 */
public final class LedgerEditBridge implements EditIntegration {
    static final int REQUIRED_API_VERSION = 1;
    private static final long FIRST_RETRY_MS = 250;
    private static final long MAX_RETRY_MS = 5_000;

    // Null when the installed Ledger supports the producer batch contract.
    private final String incompatibility;

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
    public CompletionStage<Reservation> prepare(PreparedChunkEdit proposal) {
        if (incompatibility != null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Ledger audit is required but " + incompatibility + "; no writes were made"));
        }
        LedgerApi api = LedgerApi.get();
        long changes = proposal.writes().stream().filter(delta -> !delta.isNoOp()).count();
        if (changes > api.producerBatchLimit()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Ledger records at most "
                    + api.producerBatchLimit() + " changes per chunk edit, this one has " + changes
                    + "; no writes were made"));
        }
        Instant probe = Instant.now();
        for (EditDelta delta : proposal.writes()) {
            if (delta.isNoOp()) continue;
            // The longest sequence, so a row that fits now still fits whatever sequence it gets.
            String refusal = api.validateProducerAction(
                    LedgerPayloads.action(proposal.context(), Long.MAX_VALUE, delta, probe));
            if (refusal != null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Ledger would not record the "
                        + delta.kind().name().toLowerCase() + " change at " + delta.x() + "," + delta.y() + ","
                        + delta.z() + ": " + refusal + "; no writes were made"));
            }
        }
        return CompletableFuture.completedFuture(new LedgerReservation());
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

    private static final class LedgerReservation implements Reservation {
        @Override
        public boolean authorize(ServerLevel level, ServerPlayer operator, EditActorContext context,
                EditDelta actualDelta) {
            return true;
        }

        @Override
        public CompletionStage<Void> reconcile(ServerLevel level, CommitReceipt receipt) {
            return CompletableFuture.completedFuture(null);
        }

        // Only actual applied deltas become rows; no-op and denied proposals produce none.
        @Override
        public CompletionStage<Void> record(CommitReceipt receipt) {
            if (receipt.applied().isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            Instant timestamp = Instant.now();
            List<ProducerBatch.Entry> entries = new ArrayList<>(receipt.applied().size());
            for (CommitReceipt.AppliedDelta applied : receipt.applied()) {
                entries.add(new ProducerBatch.Entry(applied.sequence(),
                        LedgerPayloads.action(receipt.context(), applied.sequence(), applied.delta(), timestamp)));
            }
            ProducerBatch batch = new ProducerBatch(LedgerPayloads.SOURCE, receipt.context().operationId(), entries);
            return append(batch, FIRST_RETRY_MS).thenApply(ack -> null);
        }

        @Override
        public void close(ServerLevel level) {
        }

        @Override
        public void discard() {
        }
    }

    // Transient refusals retry with backoff; the unit keeps its admission slot, so FAWE admits
    // no new work while Ledger is behind. Conflicts and invalid rows fail at once.
    private static CompletionStage<BatchAck> append(ProducerBatch batch, long delayMs) {
        return LedgerApi.get().appendBatch(batch).handle((ack, failure) -> {
            if (failure == null) return CompletableFuture.completedFuture(ack);
            ProducerBatchException refused = refusal(failure);
            if (refused != null && (refused.reason() == ProducerBatchException.Reason.BACKPRESSURE
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
}
