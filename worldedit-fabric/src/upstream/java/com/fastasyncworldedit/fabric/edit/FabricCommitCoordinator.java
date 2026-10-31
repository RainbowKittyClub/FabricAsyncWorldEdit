package com.fastasyncworldedit.fabric.edit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.fastasyncworldedit.fabric.compat.EditIntegration;

/** Bounded, owner-thread publication with explicit receipts and nonblocking finalization. */
public final class FabricCommitCoordinator {
    private final MinecraftServer server;
    private final FabricWorldAccess access;
    private final List<EditIntegration> integrations;
    private final Executor detachedExecutor;
    private final int maxPending;
    private final long maxBytes;
    private final Map<UUID, Operation> operations = new LinkedHashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    private long retainedBytes;
    private final java.util.Set<String> quarantined = new java.util.HashSet<>();
    private boolean stopped;
    private CompletionStage<Void> shutdown;

    /** Construct on the server thread; the lifecycle owner must call tick and stop. */
    public FabricCommitCoordinator(MinecraftServer server, FabricWorldAccess access,
            List<EditIntegration> integrations, Executor detachedExecutor, int maxPending, long maxBytes) {
        this.server = Objects.requireNonNull(server);
        this.access = Objects.requireNonNull(access);
        this.integrations = List.copyOf(integrations);
        com.fastasyncworldedit.fabric.compat.EditIntegrations.validate(this.integrations);
        this.detachedExecutor = Objects.requireNonNull(detachedExecutor);
        if (maxPending < 1 || maxBytes < 1) {
            throw new IllegalArgumentException("Commit budgets must be positive");
        }
        this.maxPending = maxPending;
        this.maxBytes = maxBytes;
        owner();
    }

    /** Register explicit authority before any preparation; admission never waits on capacity. */
    public synchronized Operation admit(EditActorContext context,
            Function<CommitReceipt, CompletionStage<Void>> history) {
        Objects.requireNonNull(history, "An explicit history completion policy is required");
        if (stopped || operations.size() >= maxPending) {
            throw new RejectedExecutionException("Operation admission is closed or full");
        }
        Operation operation = new Operation(context, history);
        if (operations.putIfAbsent(context.operationId(), operation) != null) {
            throw new IllegalArgumentException("Operation identity is already admitted");
        }
        return operation;
    }

    /**
     * Register completion before scheduling work. Capacity refusal is visible backpressure,
     * never a blocking wait inside a worker pool or on the server thread.
     */
    public synchronized CompletionStage<CommitReceipt> submitCommit(PreparedChunkEdit proposal) {
        return submitCommit(proposal, receipt -> CompletableFuture.completedFuture(null));
    }

    /** Register a queue completion consumer before publication becomes eligible. */
    public synchronized CompletionStage<CommitReceipt> submitCommit(PreparedChunkEdit proposal,
            Function<CommitReceipt, CompletionStage<Void>> afterHistory) {
        Objects.requireNonNull(afterHistory);
        Operation operation = operations.get(proposal.context().operationId());
        if (stopped || operation == null || operation.sealed || !operation.context.equals(proposal.context())) {
            throw new RejectedExecutionException("Operation is absent, sealed, stopped or has another context");
        }
        long bytes = proposal.estimatedBytes();
        if (pending.size() >= maxPending || bytes > maxBytes - retainedBytes) {
            throw new RejectedExecutionException("Bounded commit admission is full");
        }
        Pending unit = new Pending(operation, proposal, bytes, afterHistory);
        pending.add(unit);
        retainedBytes += bytes;
        operation.units.add(unit.completion);
        try {
            detachedExecutor.execute(() -> prepare(unit));
        } catch (RuntimeException failure) {
            unit.preparationFailure = failure;
            unit.preparationDone.completeExceptionally(failure);
            unit.ready = true;
        }
        return unit.completion.minimalCompletionStage();
    }

    /** Publish at most one unit per heartbeat; unfinished same-chunk work retains its lane. */
    public void tick() {
        owner();
        Pending chosen = null;
        synchronized (this) {
            for (Pending unit : pending) {
                if (unit.busy || !unit.ready) {
                    continue;
                }
                boolean blocked = false;
                for (Pending earlier : pending) {
                    if (earlier == unit) {
                        break;
                    }
                    if (sameChunk(earlier.proposal, unit.proposal)) {
                        blocked = true;
                        break;
                    }
                }
                if (!blocked) {
                    chosen = unit;
                    chosen.busy = true;
                    break;
                }
            }
        }
        if (chosen != null) {
            publish(chosen);
        }
    }

    /**
     * Stop owner-thread work immediately; return a drain covering late detached cleanup/recording.
     * The lifecycle owner must await that drain off-thread before releasing integration executors.
     */
    public synchronized CompletionStage<Void> stop() {
        owner();
        if (stopped) {
            return shutdown;
        }
        stopped = true;
        List<Pending> stopping = List.copyOf(pending);
        List<CompletableFuture<?>> draining = new ArrayList<>();
        for (Pending unit : stopping) {
            draining.add(unit.preparationDone);
            draining.add(unit.finalizationDone);
            draining.add(unit.releaseDone);
        }
        shutdown = CompletableFuture.allOf(draining.toArray(CompletableFuture[]::new)).minimalCompletionStage();
        List<Operation> admitted = List.copyOf(operations.values());
        for (Operation operation : admitted) {
            operation.context.cancellation().cancel();
        }
        for (Pending unit : stopping) {
            CommitReceipt receipt = unit.receipt;
            if (receipt == null) {
                unit.finalizationDone.complete(null);
                receipt = new CommitReceipt(unit.proposal.context(), unit.proposal.chunkX(), unit.proposal.chunkZ(),
                        List.of(), 0, 0, CommitReceipt.Outcome.CANCELLED_BEFORE_WRITE, "");
                unit.operation.published.add(receipt);
            }
            Throwable failure = new RejectedExecutionException("Server stopped before required finalization completed");
            finish(unit, receipt, failure);
        }
        for (Operation operation : admitted) {
            if (operation.units.isEmpty()) {
                operation.completion.completeExceptionally(new RejectedExecutionException("Server stopped before submission"));
            }
            operation.seal();
        }
        return shutdown;
    }

    /** Current retained admission bytes, including in-flight recording. */
    public synchronized long retainedBytes() {
        return retainedBytes;
    }

    /** Number of admitted units, including asynchronous finalization. */
    public synchronized int pendingCount() {
        return pending.size();
    }

    /** Explicit operation lifetime; sealing prevents races with completion registration. */
    public final class Operation {
        private final EditActorContext context;
        private final Function<CommitReceipt, CompletionStage<Void>> history;
        private final List<CompletableFuture<CommitReceipt>> units = new ArrayList<>();
        private final List<CommitReceipt> published = new ArrayList<>();
        private final CompletableFuture<List<CommitReceipt>> completion = new CompletableFuture<>();
        private long sequence;
        private boolean sealed;
        private Phase phase = Phase.ADMITTED;

        // Admission copies authority once; child units must carry the identical context.
        private Operation(EditActorContext context, Function<CommitReceipt, CompletionStage<Void>> history) {
            this.context = Objects.requireNonNull(context);
            this.history = history;
        }

        /** Seal submission and await every registered unit, including failed finalization. */
        public CompletionStage<List<CommitReceipt>> seal() {
            synchronized (FabricCommitCoordinator.this) {
                if (!sealed) {
                    sealed = true;
                    CompletableFuture.allOf(units.toArray(CompletableFuture[]::new)).whenComplete((unused, failure) -> {
                        synchronized (FabricCommitCoordinator.this) {
                            operations.remove(context.operationId(), this);
                            if (failure != null) {
                                completion.completeExceptionally(failure);
                            } else {
                                phase = Phase.COMPLETE;
                                completion.complete(units.stream().map(CompletableFuture::join).toList());
                            }
                        }
                    });
                }
            }
            return completion.minimalCompletionStage();
        }

        /** Publication facts survive exceptional completion and cannot be modified by callers. */
        public List<CommitReceipt> publishedReceipts() {
            synchronized (FabricCommitCoordinator.this) {
                return List.copyOf(published);
            }
        }

        /** Latest observed milestone; terminal unit outcomes remain in their receipts. */
        public Phase phase() {
            synchronized (FabricCommitCoordinator.this) {
                return phase;
            }
        }
    }

    /** Observable milestones do not substitute for the completion future. */
    public enum Phase {
        ADMITTED, PREPARING, READY, PUBLISHED, RECONCILED, RECORDED, COMPLETE
    }

    /** Exceptional completion still carries the exact applied subset. */
    public static final class CommitFailure extends RuntimeException {
        private final CommitReceipt receipt;

        /** Preserve publication facts alongside the original callback/write failure. */
        public CommitFailure(CommitReceipt receipt, Throwable cause) {
            super(receipt.failure(), cause);
            this.receipt = receipt;
        }

        /** Actual effects, never speculative proposals. */
        public CommitReceipt receipt() {
            return receipt;
        }
    }

    // Prepare detached integration state by chaining stages, never waiting in the executor.
    private void prepare(Pending unit) {
        synchronized (this) {
            if (unit.terminal) {
                unit.preparationDone.complete(null);
                return;
            }
            unit.operation.phase = Phase.PREPARING;
        }
        CompletionStage<Void> preparation = CompletableFuture.completedFuture(null);
        for (EditIntegration integration : integrations) {
            preparation = preparation.thenComposeAsync(unused -> {
                synchronized (this) {
                    if (unit.terminal) {
                        return CompletableFuture.completedFuture(null);
                    }
                }
                for (EditDelta delta : unit.proposal.writes()) {
                    if (!integration.capabilities().contains(delta.kind())) {
                        throw new UnsupportedOperationException(integration.id() + " does not support " + delta.kind());
                    }
                }
                return Objects.requireNonNull(integration.prepare(unit.proposal), "Integration preparation")
                        .thenAccept(reservation -> {
                            Objects.requireNonNull(reservation);
                            synchronized (this) {
                                if (!unit.terminal) {
                                    unit.reservations.add(reservation);
                                    return;
                                }
                            }
                            reservation.discard();
                        });
            }, detachedExecutor);
        }
        preparation.whenComplete((unused, failure) -> {
            synchronized (this) {
                if (failure == null) {
                    unit.preparationDone.complete(null);
                } else {
                    unit.preparationDone.completeExceptionally(failure);
                }
                if (unit.terminal) {
                    return;
                }
                unit.preparationFailure = failure;
                unit.operation.phase = Phase.READY;
                unit.ready = true;
            }
        });
    }

    // All reads, authorization and corresponding writes occur without an owner-thread yield.
    private void publish(Pending unit) {
        PreparedChunkEdit proposal = unit.proposal;
        List<CommitReceipt.AppliedDelta> applied = new ArrayList<>();
        List<EditDelta> authorized = new ArrayList<>();
        int denied = 0;
        int noOp = 0;
        Throwable failure = unit.preparationFailure;
        CommitReceipt.Outcome outcome = CommitReceipt.Outcome.COMPLETE;
        ServerLevel level = null;
        try {
            if (failure != null) {
                throw new IllegalStateException("Integration preparation failed", failure);
            }
            level = access.level(proposal.context().dimension());
            unit.level = level;
            if (quarantined.contains(chunkKey(proposal))) {
                throw new IllegalStateException("Chunk is quarantined after a publication/finalization failure");
            }
            if (proposal.context().cancellation().isCancelled() || stopped) {
                outcome = CommitReceipt.Outcome.CANCELLED_BEFORE_WRITE;
            } else {
                ServerPlayer player = server.getPlayerList().getPlayer(proposal.context().actorId());
                if (player == null || player.hasDisconnected()
                        || !FabricRegionCommands.authorized(proposal.context(), player)) {
                    throw new IllegalStateException("Current operator is disconnected or command authority is unavailable");
                }
                if (!access.matches(proposal, level)) {
                    outcome = CommitReceipt.Outcome.CONFLICT;
                } else {
                    access.validateSupported(proposal, level);
                    for (EditDelta delta : proposal.writes()) {
                        EditValue before = access.read(level, delta);
                        EditDelta actual = new EditDelta(delta.kind(), delta.x(), delta.y(), delta.z(),
                                delta.key(), before, delta.after());
                        if (actual.isNoOp()) {
                            noOp++;
                            continue;
                        }
                        boolean allowed = true;
                        for (EditIntegration.Reservation reservation : unit.reservations) {
                            allowed &= reservation.authorize(level, player, proposal.context(), actual);
                        }
                        if (allowed) {
                            authorized.add(actual);
                        } else {
                            denied++;
                        }
                    }
                    for (EditIntegration.Reservation reservation : unit.reservations) {
                        reservation.beforePublication(level, List.copyOf(authorized));
                    }
                    if (!access.matches(proposal, level)) {
                        throw new IllegalStateException("A prepublication callback changed the read footprint");
                    }
                    for (List<EditDelta> group : access.publicationUnits(authorized)) {
                        List<EditDelta> permitted = new ArrayList<>();
                        for (EditDelta delta : group) {
                            boolean stillAllowed = !proposal.context().cancellation().isCancelled()
                                    && FabricRegionCommands.authorized(proposal.context(), player);
                            for (EditIntegration.Reservation reservation : unit.reservations) {
                                stillAllowed &= reservation.authorize(level, player, proposal.context(), delta);
                            }
                            if (stillAllowed) permitted.add(delta);
                            else denied++;
                        }
                        for (EditDelta delta : permitted) {
                            if (!Objects.equals(access.read(level, delta), delta.before())) {
                                throw new IllegalStateException("Authorization changed an intended before-value");
                            }
                        }
                        try {
                            access.writeUnit(level, permitted, proposal.context().sideEffects());
                        } finally {
                            for (EditDelta delta : permitted) {
                                EditValue actualAfter = access.read(level, delta);
                                if (!Objects.equals(delta.before(), actualAfter)) {
                                    applied.add(new CommitReceipt.AppliedDelta(unit.operation.sequence++,
                                            new EditDelta(delta.kind(), delta.x(), delta.y(), delta.z(), delta.key(),
                                                    delta.before(), actualAfter)));
                                }
                            }
                        }
                    }
                    if (denied != 0) {
                        outcome = CommitReceipt.Outcome.PARTIAL;
                    }
                }
            }
        } catch (Throwable problem) {
            failure = problem;
            outcome = applied.isEmpty() ? CommitReceipt.Outcome.FAILED_BEFORE_WRITE : CommitReceipt.Outcome.PARTIAL;
            proposal.context().cancellation().cancel();
        }
        CommitReceipt receipt = new CommitReceipt(proposal.context(), proposal.chunkX(), proposal.chunkZ(),
                applied, denied, noOp, outcome, failure == null ? "" : failure.toString());
        synchronized (this) {
            unit.receipt = receipt;
            unit.operation.published.add(receipt);
            unit.operation.phase = Phase.PUBLISHED;
        }
        finalizeUnit(unit, level, receipt, failure);
    }

    // A maintenance/recording failure is never allowed to resubmit a world mutation.
    private void finalizeUnit(Pending unit, ServerLevel level, CommitReceipt receipt, Throwable writeFailure) {
        CompletionStage<Void> finalized = CompletableFuture.completedFuture(null);
        var finalizationFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>(writeFailure);
        var historyFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        CompletionStage<Void> writtenHistory = CompletableFuture.completedFuture(null).thenComposeAsync(
                unused -> Objects.requireNonNull(unit.operation.history.apply(receipt)), detachedExecutor)
                .handle((unused, failure) -> {
                    historyFailure.set(failure);
                    if (failure != null) finalizationFailure.compareAndSet(null, failure);
                    return (Void) null;
                });
        if (level != null) {
            for (EditIntegration.Reservation reservation : unit.reservations) {
                CompletionStage<Void> maintenance;
                try {
                    maintenance = Objects.requireNonNull(reservation.reconcile(level, receipt));
                } catch (Throwable failure) {
                    maintenance = CompletableFuture.failedFuture(failure);
                }
                finalized = finalized.thenCombine(maintenance, (left, right) -> null);
            }
            finalized = finalized.handle((unused, failure) -> {
                if (failure != null) finalizationFailure.compareAndSet(null, failure);
                return (Void) null;
            }).thenCompose(unused -> access.completePublication(level, receipt, finalizationFailure.get() == null))
                    .thenCompose(unused -> {
                        owner();
                        if (unit.terminal || finalizationFailure.get() != null) return CompletableFuture.completedFuture(null);
                        CompletionStage<Void> publication = CompletableFuture.completedFuture(null);
                        for (EditIntegration.Reservation reservation : unit.reservations) {
                            CompletionStage<Void> stage;
                            try {
                                stage = Objects.requireNonNull(reservation.publish(level, receipt));
                            } catch (Throwable failure) {
                                stage = CompletableFuture.failedFuture(failure);
                            }
                            publication = publication.thenCombine(stage, (left, right) -> null);
                        }
                        return publication;
                    });
        }
        finalized = finalized.handle((unused, failure) -> {
            if (failure != null) {
                finalizationFailure.compareAndSet(null, failure);
            }
            return (Void) null;
        }).thenCombine(writtenHistory, (left, right) -> (Void) null).thenComposeAsync(unused -> {
            synchronized (this) {
                if (unit.terminal) {
                    throw new RejectedExecutionException("Shutdown interrupted finalization");
                }
                unit.operation.phase = Phase.RECONCILED;
            }
            CompletionStage<Void> recorded;
            try {
                recorded = historyFailure.get() == null ? Objects.requireNonNull(unit.afterHistory.apply(receipt))
                        : CompletableFuture.failedFuture(historyFailure.get());
            } catch (Throwable failure) {
                recorded = CompletableFuture.failedFuture(failure);
            }
            for (EditIntegration.Reservation reservation : unit.reservations) {
                recorded = recorded.handle((ignored, failure) -> {
                    if (failure != null) {
                        finalizationFailure.compareAndSet(null, failure);
                    }
                    return (Void) null;
                }).thenComposeAsync(ignored -> Objects.requireNonNull(reservation.record(receipt)), detachedExecutor);
            }
            return recorded;
        }, detachedExecutor);
        finalized.whenComplete((unused, failure) -> {
            synchronized (this) {
                Throwable problem = failure == null ? finalizationFailure.get() : failure;
                if (problem == null) {
                    unit.finalizationDone.complete(null);
                } else {
                    unit.finalizationDone.completeExceptionally(problem);
                }
                if (unit.terminal) {
                    return;
                }
                server.execute(() -> finish(unit, receipt, problem));
            }
        });
    }

    // Shutdown and normal completion race through one owner-thread release and settlement point.
    private synchronized void finish(Pending unit, CommitReceipt receipt, Throwable problem) {
        owner();
        if (unit.terminal) {
            return;
        }
        unit.terminal = true;
        Throwable releaseFailure = null;
        for (EditIntegration.Reservation reservation : unit.reservations) {
            try {
                if (unit.level == null) {
                    reservation.discard();
                } else {
                    reservation.close(unit.level);
                }
            } catch (Throwable closeFailure) {
                releaseFailure = closeFailure;
                if (problem == null) {
                    problem = closeFailure;
                } else {
                    problem.addSuppressed(closeFailure);
                }
            }
        }
        if (releaseFailure == null) {
            unit.releaseDone.complete(null);
        } else {
            unit.releaseDone.completeExceptionally(releaseFailure);
        }
        CommitReceipt terminal = receipt;
        if (problem != null) {
            unit.operation.context.cancellation().cancel();
            if (!receipt.applied().isEmpty()) {
                quarantined.add(chunkKey(unit.proposal));
            }
            terminal = new CommitReceipt(receipt.context(), receipt.chunkX(), receipt.chunkZ(), receipt.applied(),
                    receipt.denied(), receipt.noOp(), receipt.applied().isEmpty()
                            ? receipt.outcome() == CommitReceipt.Outcome.CANCELLED_BEFORE_WRITE
                                    ? receipt.outcome() : CommitReceipt.Outcome.FAILED_BEFORE_WRITE
                            : CommitReceipt.Outcome.APPLIED_BUT_FINALIZATION_FAILED, problem.toString());
        }
        pending.remove(unit);
        retainedBytes -= unit.bytes;
        if (problem == null) {
            unit.operation.phase = Phase.RECORDED;
            unit.completion.complete(terminal);
        } else {
            unit.completion.completeExceptionally(new CommitFailure(terminal, problem));
        }
    }

    // Quarantine keys cannot alias dimensions or signed chunk coordinates.
    private static String chunkKey(PreparedChunkEdit proposal) {
        return proposal.context().dimension() + "/" + proposal.chunkX() + "/" + proposal.chunkZ();
    }

    // A retained lane includes reconciliation and recording, not merely the palette write.
    private static boolean sameChunk(PreparedChunkEdit left, PreparedChunkEdit right) {
        return left.context().dimension().equals(right.context().dimension())
                && left.chunkX() == right.chunkX() && left.chunkZ() == right.chunkZ();
    }

    // Fail immediately rather than enqueueing a blocking owner-thread call.
    private void owner() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Commit coordinator requires the server thread");
        }
    }

    // Future registration precedes executor submission; only detached data crosses to preparation.
    private static final class Pending {
        private final Operation operation;
        private final PreparedChunkEdit proposal;
        private final long bytes;
        private final Function<CommitReceipt, CompletionStage<Void>> afterHistory;
        private final CompletableFuture<CommitReceipt> completion = new CompletableFuture<>();
        private final CompletableFuture<Void> preparationDone = new CompletableFuture<>();
        private final CompletableFuture<Void> finalizationDone = new CompletableFuture<>();
        private final CompletableFuture<Void> releaseDone = new CompletableFuture<>();
        private final List<EditIntegration.Reservation> reservations = new ArrayList<>();
        private volatile boolean ready;
        private volatile Throwable preparationFailure;
        private boolean busy;
        private boolean terminal;
        private ServerLevel level;
        private CommitReceipt receipt;

        // Store immutable input and owned completion before it becomes visible to workers.
        private Pending(Operation operation, PreparedChunkEdit proposal, long bytes,
                Function<CommitReceipt, CompletionStage<Void>> afterHistory) {
            this.operation = operation;
            this.proposal = proposal;
            this.bytes = bytes;
            this.afterHistory = afterHistory;
        }
    }
}
