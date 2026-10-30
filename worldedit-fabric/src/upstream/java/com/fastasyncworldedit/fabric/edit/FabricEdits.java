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
    private final FabricStateMapping mapping;
    private final FabricWorldAccess access;
    private final FabricCommitCoordinator coordinator;
    private final ThreadPoolExecutor preparation = executor("fawe-coordinated-preparation");
    private final ThreadPoolExecutor recording = executor("fawe-coordinated-recording");
    private final Map<CompletableFuture<List<CommitReceipt>>, EditActorContext> admitted = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean stopped;
    private CompletionStage<Void> drain = CompletableFuture.completedFuture(null);

    // The service is constructed only by the opt-in server lifecycle.
    private FabricEdits(MinecraftServer server) {
        this.server = server;
        mapping = new FabricStateMapping(server);
        access = new FabricWorldAccess(server, mapping);
        coordinator = new FabricCommitCoordinator(server, access, EditIntegrations.discover(), recording, 32, 64L << 20);
    }

    /** Register lifecycle hooks once, without enabling the experimental backend by default. */
    public static void initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (Boolean.getBoolean("fawe.experimental.coordinated")) {
                active = new FabricEdits(server);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (active != null && active.server == server) {
                active.coordinator.tick();
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
        if (active == null || active.server != server || active.stopped) {
            throw new IllegalStateException("Enable -Dfawe.experimental.coordinated=true to use the experimental queue API");
        }
        return active;
    }

    /**
     * Admit loaded snapshots on the server thread, then invoke normal FAWE queue APIs off-thread.
     * The callback must not escape its queue. S3 supports at most four loaded chunks, 4096 writes
     * per submitted chunk, and explicit no-neighbor side effects; cold loads belong to S4.
     */
    public CompletionStage<List<CommitReceipt>> submit(EditActorContext context, List<BlockVector2> footprint,
            ChangeSet history, Consumer<SingleThreadQueueExtent> edit) {
        owner();
        Objects.requireNonNull(history);
        if (!(history instanceof com.sk89q.worldedit.history.changeset.ArrayListHistory)) {
            throw new UnsupportedOperationException("S3 requires synchronous in-memory history; durable histories need an acknowledgement adapter");
        }
        Objects.requireNonNull(edit);
        List<BlockVector2> positions = List.copyOf(footprint);
        if (stopped || admitted.size() >= 8 || positions.isEmpty() || positions.size() > 4
                || positions.stream().distinct().count() != positions.size()) {
            throw new IllegalArgumentException("Loaded operation admission is closed, full or outside its footprint budget");
        }
        var level = access.level(context.dimension());
        Map<BlockVector2, FabricGetBlocks> snapshots = new LinkedHashMap<>();
        var operation = coordinator.admit(context, receipt -> {
            record(history, receipt);
            return CompletableFuture.completedFuture(null);
        });
        CompletableFuture<List<CommitReceipt>> result = new CompletableFuture<>();
        admitted.put(result, context);
        result.whenComplete((receipts, failure) -> {
            synchronized (admitted) {
                admitted.remove(result);
            }
        });
        try {
            FabricGetBlocks.Submitter submitter = new FabricGetBlocks.Submitter() {
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
            for (BlockVector2 pos : positions) {
                snapshots.put(pos, FabricGetBlocks.capture(level, pos.x(), pos.z(), mapping, submitter));
            }
            int minY = level.getMinY();
            int maxY = level.getMaxY();
            preparation.execute(() -> execute(context, operation, snapshots, minY, maxY, edit, result));
        } catch (Throwable failure) {
            context.cancellation().cancel();
            operation.seal().whenComplete((receipts, ignored) -> result.completeExceptionally(
                    new OperationFailure(context, operation.publishedReceipts(), failure)));
        }
        return result.minimalCompletionStage();
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
    private void execute(EditActorContext context, FabricCommitCoordinator.Operation operation,
            Map<BlockVector2, FabricGetBlocks> snapshots, int minY, int maxY,
            Consumer<SingleThreadQueueExtent> edit, CompletableFuture<List<CommitReceipt>> result) {
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
        Throwable preparationFailure = problem;
        operation.seal().whenComplete((receipts, failure) -> {
            Throwable terminal = preparationFailure == null ? failure : preparationFailure;
            if (terminal == null) {
                result.complete(receipts);
            } else {
                result.completeExceptionally(new OperationFailure(context, operation.publishedReceipts(), terminal));
            }
        });
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
        synchronized (admitted) {
            admitted.values().forEach(context -> context.cancellation().cancel());
        }
        CompletableFuture<?>[] producers = admitted.keySet().stream()
                .map(future -> future.handle((value, failure) -> null)).toArray(CompletableFuture[]::new);
        CompletionStage<Void> publications = coordinator.stop();
        preparation.shutdown();
        drain = publications.handle((unused, failure) -> failure).thenCombine(
                CompletableFuture.allOf(producers), (failure, unused) -> failure).thenCompose(failure -> {
                    recording.shutdown();
                    return CompletableFuture.runAsync(() -> {
                        try {
                            if (!preparation.awaitTermination(10, TimeUnit.SECONDS)
                                    || !recording.awaitTermination(10, TimeUnit.SECONDS)) {
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
