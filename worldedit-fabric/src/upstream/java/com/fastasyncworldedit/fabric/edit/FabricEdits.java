package com.fastasyncworldedit.fabric.edit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.queue.IQueueExtent;
import com.fastasyncworldedit.core.queue.implementation.SingleThreadQueueExtent;
import com.fastasyncworldedit.core.queue.implementation.blocks.CharSetBlocks;
import com.fastasyncworldedit.fabric.compat.EditIntegrations;
import com.sk89q.worldedit.extent.NullExtent;
import com.sk89q.worldedit.fabric.internal.NBTConverter;
import com.sk89q.worldedit.history.change.BiomeChange3D;
import com.sk89q.worldedit.history.change.BlockChange;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.biome.BiomeTypes;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.MinecraftServer;

/** Experimental asynchronous FAWE queue API; ordinary WorldEdit commands retain the WNA backend. */
public final class FabricEdits {
    private static FabricEdits active;
    private final MinecraftServer server;
    private FabricStateMapping mapping;
    private final FabricWorldAccess access;
    private final FabricCommitCoordinator coordinator;
    private final FabricPreparationService preparation;
    private final ThreadPoolExecutor recording = executor("fawe-coordinated-recording");
    private final Map<CompletableFuture<List<CommitReceipt>>, EditActorContext> admitted = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean stopped;
    private long repreparations;
    private CompletionStage<Void> drain = CompletableFuture.completedFuture(null);

    // The service is constructed only by the opt-in server lifecycle.
    private FabricEdits(MinecraftServer server) {
        this.server = server;
        mapping = new FabricStateMapping(server);
        access = new FabricWorldAccess(server, mapping);
        preparation = new FabricPreparationService(server, access);
        coordinator = new FabricCommitCoordinator(server, access, EditIntegrations.discover(), recording, 32, 64L << 20);
    }

    /** Register lifecycle hooks once, without enabling the experimental backend by default. */
    public static void initialize() {
        FabricRegionCommands.initialize();
        FabricChunkTickets.register();
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resources) -> {
            if (active != null && active.server == server) {
                active.preparation.reload(true);
                active.access.invalidateMapping();
            }
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (active != null && active.server == server) {
                active.mapping = new FabricStateMapping(server);
                active.access.replaceMapping(active.mapping);
                active.preparation.reload(false);
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (Boolean.getBoolean("fawe.experimental.coordinated")) {
                active = new FabricEdits(server);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (active != null && active.server == server) {
                active.coordinator.tick();
                active.preparation.tick();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (active != null && active.server == server) {
                active.stop();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (active != null && active.server == server) {
                active = null;
            }
        });
    }

    /** Select the coordinated API explicitly; an unavailable backend is never silently bypassed. */
    public static FabricEdits get(MinecraftServer server) {
        if (!Boolean.getBoolean("fawe.experimental.coordinated")
                || active == null || active.server != server || active.stopped) {
            throw new IllegalStateException("Enable -Dfawe.experimental.coordinated=true to use the experimental queue API");
        }
        return active;
    }

    /** Native writes use the same coordinator even when experimental section publication is off. */
    public static FabricEdits nativeService(MinecraftServer server) {
        if (!server.isSameThread() || server.isStopped()) {
            throw new IllegalStateException("Native admission requires a running server owner thread");
        }
        if (active == null) {
            active = new FabricEdits(server);
        }
        if (active.server != server || active.stopped) {
            throw new IllegalStateException("Native edit service is unavailable");
        }
        return active;
    }

    /** Admit one explicit native cell; completion includes actual history and provider finalization. */
    public CompletionStage<CommitReceipt> submitNative(EditActorContext context, EditDelta.Kind kind,
            BlockVector3 pos, EditValue target, Consumer<CommitReceipt> history) {
        owner();
        Objects.requireNonNull(context, "Native writes require an explicitly bound actor");
        Objects.requireNonNull(history);
        var level = access.level(context.dimension());
        // Full images may reach detached provider preparation only after sensitive-read authorization.
        var chunk = level.getChunk(pos.x() >> 4, pos.z() >> 4);
        if (kind == EditDelta.Kind.BLOCK && chunk.getBlockEntity(
                new net.minecraft.core.BlockPos(pos.x(), pos.y(), pos.z())) != null) {
            com.fastasyncworldedit.fabric.compat.NativeClaims.snapshot(level, context, pos.x() >> 4, pos.z() >> 4);
        }
        EditValue before = access.read(level, kind, pos.x(), pos.y(), pos.z(), "");
        if (kind == EditDelta.Kind.BLOCK && target.nbt() == null && before.nbt() != null
                && mapping.nativeState(before.id()).getBlock() == mapping.nativeState(target.id()).getBlock()) {
            target = new EditValue(target.id(), before.nbt());
        }
        var dependency = new PreparedChunkEdit.ReadDependency(kind, pos.x(), pos.y(), pos.z(), "", before,
                ((ChunkRevision) chunk).worldedit$editRevision());
        var proposal = new PreparedChunkEdit(context, pos.x() >> 4, pos.z() >> 4, mapping.generation(),
                List.of(dependency), List.of(new EditDelta(kind, pos.x(), pos.y(), pos.z(), "", before, target)));
        var operation = coordinator.admit(context, receipt -> {
            history.accept(receipt);
            return CompletableFuture.completedFuture(null);
        });
        try {
            CompletionStage<CommitReceipt> completion = coordinator.submitNative(proposal);
            operation.seal();
            return completion;
        } catch (Throwable failure) {
            operation.seal();
            throw failure;
        }
    }

    /** Legacy callbacks have unknown dependencies and are refused before snapshot exposure. */
    @Deprecated
    public CompletionStage<List<CommitReceipt>> submit(EditActorContext context, List<BlockVector2> footprint,
            ChangeSet history, Consumer<SingleThreadQueueExtent> edit) {
        return submitUnits(context, List.of(new Unit(footprint, edit)), history);
    }

    /**
     * Declare every write independent: clipping any cell must leave all other writes valid.
     * Moves, swaps and callbacks relying on another write's success must not use this entry point.
     */
    public CompletionStage<List<CommitReceipt>> submitIndependent(EditActorContext context,
            List<BlockVector2> footprint, ChangeSet history, Consumer<SingleThreadQueueExtent> edit) {
        return submitUnits(context, List.of(Unit.independent(footprint, edit)), history);
    }

    /**
     * Retry an independent single-destination callback at most twice after a zero-write conflict.
     * Every cell must remain valid if another is denied; compound callbacks are unsupported.
     * The callback must be deterministic from its fresh snapshots and have no external effects.
     */
    public CompletionStage<List<CommitReceipt>> submitRetrying(EditActorContext context,
            BlockVector2 destination, ChangeSet history, Consumer<SingleThreadQueueExtent> repeatable) {
        owner();
        return retry(context, destination, history, repeatable, 0);
    }

    // A published or failed mutation is never retried, including a notification failure.
    private CompletionStage<List<CommitReceipt>> retry(EditActorContext context, BlockVector2 destination,
            ChangeSet history, Consumer<SingleThreadQueueExtent> repeatable, int attempt) {
        CompletableFuture<List<CommitReceipt>> result = new CompletableFuture<>();
        submitIndependent(context, List.of(destination), history, repeatable).whenComplete((receipts, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure);
            } else if (attempt < 2 && receipts.size() == 1
                    && receipts.getFirst().outcome() == CommitReceipt.Outcome.CONFLICT
                    && receipts.getFirst().applied().isEmpty() && !context.cancellation().isCancelled()) {
                server.execute(() -> {
                    try {
                        repreparations++;
                        retry(context, destination, history, repeatable, attempt + 1).whenComplete((value, problem) -> {
                            if (problem == null) result.complete(value);
                            else result.completeExceptionally(problem);
                        });
                    } catch (Throwable problem) {
                        result.completeExceptionally(problem);
                    }
                });
            } else {
                result.complete(receipts);
            }
        });
        return result.minimalCompletionStage();
    }

    /** Publication counters include only owner-thread installs and completed maintenance. */
    public Map<String, Long> publicationStatistics() {
        owner();
        Map<String, Long> stats = new LinkedHashMap<>(access.statistics());
        stats.put("repreparations", repreparations);
        return Map.copyOf(stats);
    }

    /** Declared semantics, never inferred from matching block values or callback output. */
    public enum Semantics {
        UNSPECIFIED, INDEPENDENT, MOVE, COMPOUND, REGENERATION
    }

    /**
     * One detached callback with its declared dependencies.
     *
     * @param footprint every destination and neighbor read chunk
     * @param edit detached work; no external effects or live-world mutation
     * @param semantics independent cells may be clipped separately; all other semantics are refused
     */
    public record Unit(List<BlockVector2> footprint, Consumer<SingleThreadQueueExtent> edit, Semantics semantics) {
        /** Legacy callbacks must migrate to an explicit declaration before they can run. */
        @Deprecated
        public Unit(List<BlockVector2> footprint, Consumer<SingleThreadQueueExtent> edit) {
            this(footprint, edit, Semantics.UNSPECIFIED);
        }

        /** Declare that denying any write cannot invalidate or destroy another write's source. */
        public static Unit independent(List<BlockVector2> footprint, Consumer<SingleThreadQueueExtent> edit) {
            return new Unit(footprint, edit, Semantics.INDEPENDENT);
        }

        public Unit {
            footprint = List.copyOf(footprint);
            Objects.requireNonNull(edit);
            Objects.requireNonNull(semantics);
            if (footprint.isEmpty() || footprint.size() > 4
                    || footprint.stream().distinct().count() != footprint.size()) {
                throw new IllegalArgumentException("Unit requires one to four distinct read chunks");
            }
        }
    }

    /** Submit bounded independent units; each uses upstream queues and can prepare in parallel. */
    public CompletionStage<List<CommitReceipt>> submitUnits(EditActorContext context, List<Unit> units,
            ChangeSet history) {
        owner();
        Objects.requireNonNull(history);
        if (!(history instanceof com.sk89q.worldedit.history.changeset.ArrayListHistory)) {
            throw new UnsupportedOperationException("Durable histories need an acknowledgement adapter");
        }
        List<Unit> requests = List.copyOf(units);
        for (Unit unit : requests) {
            if (unit.semantics() != Semantics.INDEPENDENT) {
                throw new UnsupportedOperationException("No compound publication contract for " + unit.semantics());
            }
        }
        if (stopped || admitted.size() >= 8 || requests.isEmpty() || requests.size() > 64) {
            throw new IllegalArgumentException("Operation admission is closed, full or exceeds 64 units");
        }
        var operation = coordinator.admit(context, receipt -> {
            record(history, receipt);
            return CompletableFuture.completedFuture(null);
        });
        CompletableFuture<List<CommitReceipt>> result = new CompletableFuture<>();
        admitted.put(result, context);
        result.whenComplete((receipts, failure) -> admitted.remove(result));
        List<CompletableFuture<Void>> producers = new java.util.ArrayList<>();
        try {
            for (Unit unit : requests) {
                producers.add(preparation.submit(context, unit.footprint(), mapping,
                        snapshots -> submitter(context, snapshots),
                        snapshots -> execute(context, snapshots, unit.edit())).toCompletableFuture());
            }
        } catch (Throwable failure) {
            context.cancellation().cancel();
            producers.add(CompletableFuture.failedFuture(failure));
        }
        CompletableFuture.allOf(producers.toArray(CompletableFuture[]::new)).whenComplete((unused, preparationFailure) ->
                operation.seal().whenComplete((receipts, failure) -> {
                    Throwable terminal = preparationFailure == null ? failure : preparationFailure;
                    if (terminal == null) {
                        result.complete(receipts);
                    } else {
                        result.completeExceptionally(new OperationFailure(context, operation.publishedReceipts(), terminal));
                    }
                }));
        return result.minimalCompletionStage();
    }

    /** Select worker parallelism between drained operations. */
    public void preparationWorkers(int workers) {
        owner();
        if (!admitted.isEmpty()) {
            throw new IllegalStateException("Drain operations before changing preparation workers");
        }
        preparation.workers(workers);
    }

    /** Observe bounded snapshot, queue and ticket ownership on the server thread. */
    public Map<String, Long> preparationStatistics() {
        return preparation.statistics();
    }

    private FabricGetBlocks.Submitter submitter(EditActorContext context,
            Map<BlockVector2, FabricGetBlocks> snapshots) {
        return new FabricGetBlocks.Submitter() {
            @Override
            @SuppressWarnings("unchecked")
            public <T extends Future<T>> T submit(FabricGetBlocks snapshot, IQueueExtent<?> queue,
                    IChunk chunk, IChunkSet set, Runnable finalizer) {
                CoreFuture completed = new CoreFuture();
                try {
                    var post = queue.getPostProcessor();
                    post.validateCommitCompletion();
                    IChunkSet processed = queue.getProcessor().prepareCommit(chunk, snapshot, set);
                    PreparedChunkEdit proposal = FabricBufferEdits.freeze(context, snapshot, snapshots.values(), processed);
                    coordinator.submitCommit(proposal, receipt -> post.completeCommit(chunk,
                            snapshot.committedBefore(receipt), appliedSet(receipt)))
                            .whenComplete((receipt, failure) -> {
                                Throwable problem = failure;
                                try {
                                    finalizer.run();
                                } catch (Throwable callbackFailure) {
                                    if (problem == null) {
                                        problem = callbackFailure;
                                    } else {
                                        problem.addSuppressed(callbackFailure);
                                    }
                                }
                                if (problem == null) {
                                    completed.complete(null);
                                } else {
                                    context.cancellation().cancel();
                                    completed.completeExceptionally(problem);
                                }
                            });
                } catch (Throwable failure) {
                    context.cancellation().cancel();
                    completed.completeExceptionally(failure);
                }
                return (T) completed;
            }
        };
    }

    /** Failed completion retains immutable publication facts, including a failing queue finalizer. */
    public static final class OperationFailure extends RuntimeException {
        private final EditActorContext context;
        private final List<CommitReceipt> publishedReceipts;

        private OperationFailure(EditActorContext context, List<CommitReceipt> publishedReceipts, Throwable cause) {
            super("Coordinated operation failed; publication receipts do not imply finalization success", cause);
            this.context = context;
            this.publishedReceipts = List.copyOf(publishedReceipts);
        }

        /** Authority remains available even when the operation failed before any publication. */
        public EditActorContext context() {
            return context;
        }

        /** Actual publication facts, not a promise that history or notifications completed. */
        public List<CommitReceipt> publishedReceipts() {
            return publishedReceipts;
        }
    }

    /** Pending operation count, including finalization; useful for lifecycle ownership checks. */
    public int pendingOperations() {
        synchronized (admitted) {
            return admitted.size();
        }
    }

    /** Nonblocking shutdown drain, including work already in the recorder. */
    public CompletionStage<Void> shutdownDrain() {
        return drain;
    }

    // This producer never submits another preparation-pool task and waits for it.
    private void execute(EditActorContext context, Map<BlockVector2, FabricGetBlocks> snapshots,
            Consumer<SingleThreadQueueExtent> edit) {
        FabricGetBlocks first = snapshots.values().iterator().next();
        int minY = first.getMinY();
        int maxY = first.getMaxY();
        SingleThreadQueueExtent queue = new SingleThreadQueueExtent();
        queue.init(new NullExtent() {
            @Override
            public int getMinY() { return minY; }
            @Override
            public int getMaxY() { return maxY; }
        }, (x, z) -> Objects.requireNonNull(snapshots.get(BlockVector2.at(x, z)), "Read outside admitted footprint"), null);
        queue.coordinatedCompletion();
        queue.setSideEffectSet(context.sideEffects());
        Throwable problem = null;
        try {
            if (context.cancellation().isCancelled()) {
                throw new IllegalStateException("Operation cancelled before preparation");
            }
            edit.accept(queue);
        } catch (Throwable failure) {
            problem = failure;
            context.cancellation().cancel();
        }
        try {
            queue.flush();
        } catch (Throwable failure) {
            context.cancellation().cancel();
            if (problem == null) {
                problem = failure;
            } else {
                problem.addSuppressed(failure);
            }
        }
        if (problem != null) {
            throw new java.util.concurrent.CompletionException(problem);
        }
    }

    // Record exact before/after values through upstream history without its enqueue-only wrappers.
    private static void record(ChangeSet history, CommitReceipt receipt) {
        synchronized (history) {
            for (var applied : receipt.applied()) {
                EditDelta delta = applied.delta();
                BlockVector3 pos = BlockVector3.at(delta.x(), delta.y(), delta.z());
                switch (delta.kind()) {
                    case BLOCK -> history.add(new BlockChange(pos, block(delta.before()), block(delta.after())));
                    case BIOME -> history.add(new BiomeChange3D(pos, BiomeTypes.get(delta.before().id()), BiomeTypes.get(delta.after().id())));
                    default -> throw new UnsupportedOperationException("No history codec for " + delta.kind());
                }
            }
        }
    }

    // NBT converters produce fresh immutable Lin tags; receipts keep no live NBT containers.
    private static BaseBlock block(EditValue value) {
        BlockState state = BlockState.get(value.id());
        return value.nbt() == null ? state.toBaseBlock() : state.toBaseBlock(NBTConverter.fromNative(value.nbt().thaw()));
    }

    // A completion consumer sees a detached buffer containing applied cells only.
    private static IChunkSet appliedSet(CommitReceipt receipt) {
        IChunkSet set = CharSetBlocks.newInstance();
        for (var applied : receipt.applied()) {
            EditDelta delta = applied.delta();
            switch (delta.kind()) {
                case BLOCK -> set.setBlock(delta.x() & 15, delta.y(), delta.z() & 15, block(delta.after()));
                case BIOME -> set.setBiome(delta.x() & 15, delta.y(), delta.z() & 15, BiomeTypes.get(delta.after().id()));
                default -> throw new UnsupportedOperationException("No completion codec for " + delta.kind());
            }
        }
        return set;
    }

    // Stop publication before executor shutdown, so blocked producers always receive a terminal future.
    private void stop() {
        owner();
        stopped = true;
        access.stop();
        synchronized (admitted) {
            admitted.values().forEach(context -> context.cancellation().cancel());
        }
        CompletableFuture<?>[] producers = admitted.keySet().stream()
                .map(future -> future.handle((value, failure) -> null)).toArray(CompletableFuture[]::new);
        CompletionStage<Void> publications = coordinator.stop();
        CompletionStage<Void> preparationDrain = preparation.stop();
        drain = publications.handle((unused, failure) -> failure).thenCombine(
                CompletableFuture.allOf(producers), (failure, unused) -> failure).thenCombine(
                preparationDrain, (failure, unused) -> failure).thenCompose(failure -> {
                    recording.shutdown();
                    return CompletableFuture.runAsync(() -> {
                        try {
                            if (!recording.awaitTermination(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Coordinated edit executors did not drain");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new java.util.concurrent.CompletionException(interrupted);
                        }
                        if (failure != null) {
                            throw new java.util.concurrent.CompletionException(failure);
                        }
                    });
                });
    }

    // Bound producer and recorder queues independently; no lane waits on work in its own pool.
    private static ThreadPoolExecutor executor(String name) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    // Admission captures live snapshots and therefore must never run on a worker.
    private void owner() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Submit coordinated edits on the server thread; completion is asynchronous");
        }
    }

    // Upstream futures recursively return their successor; null here is the terminal success marker.
    private static final class CoreFuture extends CompletableFuture<CoreFuture> { }
}
