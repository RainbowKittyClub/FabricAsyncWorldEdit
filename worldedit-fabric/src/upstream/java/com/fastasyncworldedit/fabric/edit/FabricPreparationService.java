package com.fastasyncworldedit.fabric.edit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import com.sk89q.worldedit.math.BlockVector2;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Bounded actor-round-robin loading, owner snapshots and detached core-queue workers. */
public final class FabricPreparationService {
    public static final int MAX_QUEUED = 64;
    public static final long UNIT_BYTES = 8L << 20;
    public static final long MAX_BYTES = 32L << 20;
    private final MinecraftServer server;
    private final FabricWorldAccess access;
    private final Map<UUID, ArrayDeque<Job>> actors = new LinkedHashMap<>();
    private final List<Job> active = new ArrayList<>();
    private final Map<String, FabricChunkTickets> tickets = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> completions = new ConcurrentLinkedQueue<>();
    private final ThreadPoolExecutor workers;
    private int queued;
    private long retained;
    private long peakBytes;
    private long snapshotsCaptured;
    private long jobsStarted;
    private boolean stopped;
    private boolean reloading;

    /** Construct an owner-thread service with a bounded, independently drained preparation pool. */
    public FabricPreparationService(MinecraftServer server, FabricWorldAccess access) {
        this.server = server;
        this.access = access;
        workers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), task -> {
            Thread thread = new Thread(task, "fawe-coordinated-preparation");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        owner();
    }

    /** Select 1/2/4 preparation workers only between drained operations, never during a run. */
    public void workers(int count) {
        owner();
        if (count != 1 && count != 2 && count != 4) {
            throw new IllegalArgumentException("Preparation workers must be 1, 2 or 4");
        }
        if (queued != 0 || !active.isEmpty()) {
            throw new IllegalStateException("Drain preparation before changing its worker count");
        }
        if (count > workers.getMaximumPoolSize()) {
            workers.setMaximumPoolSize(count);
            workers.setCorePoolSize(count);
        } else {
            workers.setCorePoolSize(count);
            workers.setMaximumPoolSize(count);
        }
    }

    /** Admit a bounded footprint without retaining any world in the producer callback. */
    public CompletionStage<Void> submit(EditActorContext context, List<BlockVector2> footprint,
            FabricStateMapping mapping, Function<Map<BlockVector2, FabricGetBlocks>, FabricGetBlocks.Submitter> submitter,
            Consumer<Map<BlockVector2, FabricGetBlocks>> work) {
        owner();
        List<BlockVector2> positions = List.copyOf(footprint);
        if (stopped || reloading || queued + active.size() >= MAX_QUEUED) {
            throw new RejectedExecutionException("Preparation admission is stopped, reloading or full");
        }
        if (positions.isEmpty() || positions.size() > 4 || positions.stream().distinct().count() != positions.size()) {
            throw new IllegalArgumentException("Preparation needs one to four distinct declared read chunks");
        }
        ServerLevel level = access.level(context.dimension());
        if (level.getSectionsCount() * 20000L * positions.size() + 32768L > UNIT_BYTES) {
            throw new RejectedExecutionException("Dimension footprint exceeds the snapshot byte budget");
        }
        Job job = new Job(context, positions, mapping, submitter, work);
        actors.computeIfAbsent(context.actorId(), ignored -> new ArrayDeque<>()).add(job);
        queued++;
        return job.result.minimalCompletionStage();
    }

    /** Advance loading/snapshot work without managed blocking; copy at most one chunk per tick. */
    public void tick() {
        owner();
        for (Runnable completion; (completion = completions.poll()) != null;) {
            completion.run();
        }
        boolean captured = false;
        for (Job job : List.copyOf(active)) {
            if (job.running) {
                continue;
            }
            try {
                check(job);
                if (!job.loads.isDone()) {
                    continue;
                }
                job.loads.join();
                if (!captured && job.snapshots.size() < job.positions.size()) {
                    BlockVector2 pos = job.positions.get(job.snapshots.size());
                    long remaining = UNIT_BYTES - job.snapshots.values().stream()
                            .mapToLong(FabricGetBlocks::estimatedBytes).sum();
                    FabricGetBlocks snapshot = FabricGetBlocks.capture(access.level(job.context.dimension()),
                            pos.x(), pos.z(), job.mapping, job.submitter.apply(job.snapshots), remaining, job.context);
                    job.snapshots.put(pos, snapshot);
                    snapshotsCaptured++;
                    captured = true;
                }
                if (job.snapshots.size() == job.positions.size()) {
                    check(job);
                    for (var entry : job.snapshots.entrySet()) {
                        if (entry.getValue().containsSensitiveData()) {
                            com.fastasyncworldedit.fabric.compat.NativeClaims.snapshot(
                                    access.level(job.context.dimension()), job.context,
                                    entry.getKey().x(), entry.getKey().z());
                        }
                    }
                    Map<BlockVector2, FabricGetBlocks> detached = Map.copyOf(job.snapshots);
                    Consumer<Map<BlockVector2, FabricGetBlocks>> work = job.work;
                    job.running = true;
                    CompletableFuture.runAsync(() -> work.accept(detached), workers).whenComplete((unused, failure) -> {
                        synchronized (this) {
                            if (stopped) {
                                settleStopped(job, failure);
                            } else {
                                completions.add(() -> finish(job, failure));
                            }
                        }
                    });
                }
            } catch (Throwable failure) {
                finish(job, failure);
            }
        }
        while (!stopped && !reloading && active.size() < workers.getCorePoolSize() && !actors.isEmpty()
                && retained + UNIT_BYTES <= MAX_BYTES) {
            var first = actors.entrySet().iterator().next();
            UUID actor = first.getKey();
            ArrayDeque<Job> jobs = actors.remove(actor);
            Job job = jobs.remove();
            if (!jobs.isEmpty()) {
                actors.put(actor, jobs);
            }
            queued--;
            active.add(job);
            retained += UNIT_BYTES;
            peakBytes = Math.max(peakBytes, retained);
            jobsStarted++;
            try {
                check(job);
                FabricChunkTickets manager = tickets.computeIfAbsent(job.context.dimension(), key ->
                        new FabricChunkTickets(access.level(key), 16));
                for (BlockVector2 pos : job.positions) {
                    job.leases.add(manager.acquire(pos.x(), pos.z()));
                }
                job.loads = CompletableFuture.allOf(job.leases.stream()
                        .map(lease -> lease.loaded().toCompletableFuture()).toArray(CompletableFuture[]::new));
            } catch (Throwable failure) {
                finish(job, failure);
            }
        }
        for (var entry : List.copyOf(actors.entrySet())) {
            entry.getValue().removeIf(job -> {
                if (!job.context.cancellation().isCancelled()) {
                    return false;
                }
                queued--;
                job.result.completeExceptionally(new RejectedExecutionException("Cancelled before loading"));
                return true;
            });
            if (entry.getValue().isEmpty()) {
                actors.remove(entry.getKey());
            }
        }
    }

    /** Fence all outstanding work before resources change, including workers already preparing. */
    public void reload(boolean loading) {
        owner();
        reloading = loading;
        if (loading) {
            active.forEach(job -> job.context.cancellation().cancel());
            actors.values().forEach(jobs -> jobs.forEach(job -> job.context.cancellation().cancel()));
        }
    }

    /** Stop live work now and return the off-thread executor drain without waiting on the owner. */
    public synchronized CompletionStage<Void> stop() {
        owner();
        stopped = true;
        for (var jobs : actors.values()) {
            for (Job job : jobs) {
                job.context.cancellation().cancel();
                job.result.completeExceptionally(new RejectedExecutionException("Preparation stopped before loading"));
            }
        }
        actors.clear();
        queued = 0;
        List<CompletableFuture<Void>> draining = new ArrayList<>();
        for (Job job : List.copyOf(active)) {
            job.context.cancellation().cancel();
            release(job);
            draining.add(job.result.handle((value, failure) -> null));
            if (!job.running) {
                settleStopped(job, new RejectedExecutionException("Preparation stopped during loading"));
            }
        }
        for (Runnable completion; (completion = completions.poll()) != null;) {
            completion.run();
        }
        tickets.values().forEach(FabricChunkTickets::stop);
        workers.shutdown();
        return CompletableFuture.allOf(draining.toArray(CompletableFuture[]::new)).thenRunAsync(() -> {
            try {
                if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Preparation workers did not drain");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CompletionException(failure);
            }
        });
    }

    /** Owner-thread bounded accounting, including exact underlying ticket add/remove totals. */
    public Map<String, Long> statistics() {
        owner();
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("queued", (long) queued);
        stats.put("active", (long) active.size());
        stats.put("bytes", retained);
        stats.put("peakBytes", peakBytes);
        stats.put("workers", (long) workers.getCorePoolSize());
        stats.put("snapshots", snapshotsCaptured);
        stats.put("started", jobsStarted);
        stats.put("tickets", tickets.values().stream().mapToLong(FabricChunkTickets::size).sum());
        stats.put("acquired", tickets.values().stream().mapToLong(FabricChunkTickets::acquired).sum());
        stats.put("released", tickets.values().stream().mapToLong(FabricChunkTickets::released).sum());
        return Map.copyOf(stats);
    }

    private void check(Job job) {
        if (stopped || reloading || job.context.cancellation().isCancelled()) {
            throw new RejectedExecutionException("Preparation cancelled or registry reload in progress");
        }
    }

    private void finish(Job job, Throwable failure) {
        owner();
        release(job);
        settleStopped(job, failure);
    }

    private void release(Job job) {
        job.leases.forEach(FabricChunkTickets.Lease::close);
        job.leases.clear();
    }

    private synchronized void settleStopped(Job job, Throwable failure) {
        if (!active.remove(job)) {
            return;
        }
        retained -= UNIT_BYTES;
        job.snapshots.clear();
        if (failure == null) {
            job.result.complete(null);
        } else {
            job.context.cancellation().cancel();
            job.result.completeExceptionally(failure);
        }
    }

    private void owner() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Preparation orchestration requires the server thread");
        }
    }

    private static final class Job {
        private final EditActorContext context;
        private final List<BlockVector2> positions;
        private final FabricStateMapping mapping;
        private final Function<Map<BlockVector2, FabricGetBlocks>, FabricGetBlocks.Submitter> submitter;
        private final Consumer<Map<BlockVector2, FabricGetBlocks>> work;
        private final Map<BlockVector2, FabricGetBlocks> snapshots = new LinkedHashMap<>();
        private final List<FabricChunkTickets.Lease> leases = new ArrayList<>();
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private CompletableFuture<Void> loads;
        private boolean running;

        private Job(EditActorContext context, List<BlockVector2> positions, FabricStateMapping mapping,
                Function<Map<BlockVector2, FabricGetBlocks>, FabricGetBlocks.Submitter> submitter,
                Consumer<Map<BlockVector2, FabricGetBlocks>> work) {
            this.context = context;
            this.positions = positions;
            this.mapping = mapping;
            this.submitter = submitter;
            this.work = work;
        }
    }
}
