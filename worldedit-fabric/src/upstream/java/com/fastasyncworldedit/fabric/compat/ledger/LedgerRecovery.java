package com.fastasyncworldedit.fabric.compat.ledger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.fastasyncworldedit.fabric.edit.EditDelta;
import com.fastasyncworldedit.fabric.edit.EditValue;
import com.fastasyncworldedit.fabric.edit.FabricWorldAccess;
import com.github.quiltservertools.ledger.api.BatchAck;
import com.github.quiltservertools.ledger.api.LedgerApi;
import com.github.quiltservertools.ledger.api.ProducerBatch;
import com.github.quiltservertools.ledger.api.ProducerReceipt;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import org.apache.logging.log4j.Logger;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Restart recovery of the outbox. Ledger's stored keys are checked first, so an acknowledged unit
 * is finished whatever the world did since. Otherwise durable evidence is compared with the world:
 * an intent alone over a world still at its before values was never published and is dropped; a
 * receipt over a world at its after values is resubmitted by its keys; anything else (a later
 * edit, an applied intent whose receipt was lost, a reverted receipt) is ambiguous and quarantined,
 * never reapplied and never logged as a row it cannot prove.
 */
final class LedgerRecovery {
    private static final Logger LOGGER = LogManagerCompat.getLogger();
    private static final long READY_POLL_MS = 1_000;

    private enum Verdict { SUBMIT, DISCARD, QUARANTINE }

    private record Decision(LedgerOutbox.Unit unit, Verdict verdict, String reason) {
    }

    private final MinecraftServer server;
    private final FabricWorldAccess access;
    private final LedgerOutbox outbox;
    private final Function<ProducerBatch, CompletionStage<BatchAck>> append;
    private final BooleanSupplier stopping;
    private final AtomicInteger recorded = new AtomicInteger();

    LedgerRecovery(MinecraftServer server, FabricWorldAccess access, LedgerOutbox outbox,
            Function<ProducerBatch, CompletionStage<BatchAck>> append, BooleanSupplier stopping) {
        this.server = server;
        this.access = access;
        this.outbox = outbox;
        this.append = append;
        this.stopping = stopping;
    }

    /** Completes once every journaled unit is finished, resubmitted, dropped or quarantined. */
    CompletionStage<Void> run() {
        return outbox.load().thenCompose(units -> {
            if (units.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            return ready().thenCompose(unused -> unacknowledged(units))
                    .thenComposeAsync(remaining -> CompletableFuture.completedFuture(classify(remaining)), server)
                    .thenCompose(this::act);
        });
    }

    // Ledger's keys can be read only once its database is up; validation reports exactly that.
    private CompletionStage<Void> ready() {
        if (stopping.getAsBoolean()) {
            return CompletableFuture.failedFuture(new IllegalStateException("server stopped during Ledger audit recovery"));
        }
        if (LedgerApi.get().validateProducerAction(new FaweLedgerActions.Block()) == null) {
            return CompletableFuture.completedFuture(null);
        }
        var later = CompletableFuture.delayedExecutor(READY_POLL_MS, TimeUnit.MILLISECONDS);
        return CompletableFuture.runAsync(() -> { }, later).thenCompose(unused -> ready());
    }

    private CompletionStage<List<LedgerOutbox.Unit>> unacknowledged(List<LedgerOutbox.Unit> units) {
        // Receipt checks complete on Ledger's threads while this loop still adds intent-only units.
        List<LedgerOutbox.Unit> remaining = java.util.Collections.synchronizedList(new ArrayList<>());
        CompletionStage<Void> checked = CompletableFuture.completedFuture(null);
        for (LedgerOutbox.Unit unit : units) {
            if (unit.receipt() == null) {
                remaining.add(unit);
                continue;
            }
            checked = checked.thenCompose(unused -> LedgerApi.get()
                    .producerReceipts(LedgerPayloads.SOURCE, unit.origin().operation())
                    .thenCompose(receipts -> {
                        Set<Long> stored = new HashSet<>();
                        for (ProducerReceipt receipt : receipts) {
                            stored.add(receipt.sequence());
                        }
                        if (unit.receipt().applied().stream().allMatch(applied -> stored.contains(applied.sequence()))) {
                            recorded.incrementAndGet();
                            return outbox.remove(unit.id());
                        }
                        remaining.add(unit);
                        return CompletableFuture.completedFuture(null);
                    }));
        }
        return checked.thenApply(unused -> List.copyOf(remaining));
    }

    // Owner thread: reads the live world, loading each needed chunk; bounded by the outbox size.
    private List<Decision> classify(List<LedgerOutbox.Unit> units) {
        List<Decision> decisions = new ArrayList<>();
        for (LedgerOutbox.Unit unit : units) {
            try {
                decisions.add(classify(unit));
            } catch (RuntimeException failure) {
                decisions.add(new Decision(unit, Verdict.QUARANTINE, "world could not be read: " + failure.getMessage()));
            }
        }
        return decisions;
    }

    private Decision classify(LedgerOutbox.Unit unit) {
        ServerLevel level = access.level(unit.origin().dimension());
        List<EditDelta> relevant = unit.receipt() == null ? unit.deltas()
                : unit.receipt().applied().stream().map(applied -> unit.deltas().get(applied.index())).toList();
        int atBefore = 0;
        int atAfter = 0;
        String other = null;
        for (EditDelta delta : relevant) {
            level.getChunk(delta.x() >> 4, delta.z() >> 4);
            EditValue now = access.read(level, delta);
            if (Objects.equals(now, delta.after())) {
                atAfter++;
            } else if (Objects.equals(now, delta.before())) {
                atBefore++;
            } else if (other == null) {
                other = delta.x() + "," + delta.y() + "," + delta.z() + " holds " + now.id();
            }
        }
        String counts = atAfter + " of " + relevant.size() + " at after, " + atBefore + " at before"
                + (other == null ? "" : ", first other: " + other);
        if (unit.receipt() != null) {
            return atAfter == relevant.size() ? new Decision(unit, Verdict.SUBMIT, counts)
                    : new Decision(unit, Verdict.QUARANTINE, "receipt recorded but the world differs (" + counts + ")");
        }
        return atBefore == relevant.size() ? new Decision(unit, Verdict.DISCARD, counts)
                : new Decision(unit, Verdict.QUARANTINE, "intent without receipt but the world changed (" + counts + ")");
    }

    private CompletionStage<Void> act(List<Decision> decisions) {
        int[] counts = new int[Verdict.values().length];
        List<CompletableFuture<Void>> done = new ArrayList<>();
        for (Decision decision : decisions) {
            counts[decision.verdict().ordinal()]++;
            LedgerOutbox.Unit unit = decision.unit();
            done.add(switch (decision.verdict()) {
                case DISCARD -> outbox.remove(unit.id());
                case QUARANTINE -> outbox.quarantine(unit.id(), decision.reason());
                case SUBMIT -> resubmit(unit);
            });
        }
        return CompletableFuture.allOf(done.toArray(CompletableFuture[]::new)).thenRun(() -> LOGGER.info(
                "Ledger audit recovery: {} already recorded, {} resubmitted, {} unpublished dropped, {} quarantined",
                recorded.get(), counts[Verdict.SUBMIT.ordinal()], counts[Verdict.DISCARD.ordinal()],
                counts[Verdict.QUARANTINE.ordinal()]));
    }

    // The stored timestamp makes the rebuilt rows byte-identical to the ones first sent.
    private CompletableFuture<Void> resubmit(LedgerOutbox.Unit unit) {
        List<ProducerBatch.Entry> entries = new ArrayList<>();
        for (LedgerOutbox.Applied applied : unit.receipt().applied()) {
            entries.add(new ProducerBatch.Entry(applied.sequence(), LedgerPayloads.action(unit.origin(),
                    applied.sequence(), unit.deltas().get(applied.index()), unit.receipt().timestamp())));
        }
        ProducerBatch batch = new ProducerBatch(LedgerPayloads.SOURCE, unit.origin().operation(), entries);
        return append.apply(batch).toCompletableFuture()
                .thenCompose(ack -> outbox.remove(unit.id()))
                .exceptionallyCompose(failure -> stopping.getAsBoolean() ? CompletableFuture.completedFuture(null)
                        : outbox.quarantine(unit.id(), "Ledger refused the recovered rows: " + failure.getMessage()));
    }
}
