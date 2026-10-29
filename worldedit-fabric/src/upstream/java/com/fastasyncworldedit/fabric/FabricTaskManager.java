package com.fastasyncworldedit.fabric;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.MinecraftServer;

import com.fastasyncworldedit.core.util.TaskManager;

/** Runs delayed work on server ticks and rejects submissions outside the server lifecycle. */
public class FabricTaskManager extends TaskManager {
    private final Supplier<MinecraftServer> serverSupplier;
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2,
        Thread.ofPlatform().daemon().name("fawe-fabric-async-", 0).factory());
    private final Map<Integer, ScheduledTask> tasks = new LinkedHashMap<>();
    private int nextId = 1;
    private long tick;
    private volatile boolean stopped;

    /** Binds this manager to the current server and its tick/stop events. */
    public FabricTaskManager(Supplier<MinecraftServer> serverSupplier) {
        this.serverSupplier = serverSupplier;
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> shutdown());
    }

    @Override
    public int repeat(@Nonnull Runnable runnable, int interval) {
        return schedule(runnable, Math.max(1, interval), Math.max(1, interval), false);
    }

    @Override
    public int repeatAsync(@Nonnull Runnable runnable, int interval) {
        return schedule(runnable, Math.max(1, interval), Math.max(1, interval), true);
    }

    @Override
    public void async(@Nonnull Runnable runnable) {
        MinecraftServer server = requireServer();
        asyncExecutor.execute(() -> {
            if (requireServer() != server) {
                throw new RejectedExecutionException("FAWE server changed");
            }
            runnable.run();
        });
    }

    @Override
    public void task(@Nonnull Runnable runnable) {
        MinecraftServer server = requireServer();
        server.execute(() -> {
            if (requireServer() != server) {
                throw new RejectedExecutionException("FAWE server changed");
            }
            runnable.run();
        });
    }

    @Override
    public void taskNowMain(@Nonnull Runnable runnable) {
        task(runnable);
    }

    @Override
    public void later(@Nonnull Runnable runnable, int delay) {
        schedule(runnable, Math.max(1, delay), 0, false);
    }

    @Override
    public void laterAsync(@Nonnull Runnable runnable, int delay) {
        schedule(runnable, Math.max(1, delay), 0, true);
    }

    @Override
    public synchronized void cancel(int id) {
        ScheduledTask scheduled = tasks.remove(id);
        if (scheduled != null) {
            scheduled.cancelled = true;
        }
    }

    /** Cancels pending work without waiting for workers on the server thread. */
    public synchronized void shutdown() {
        stopped = true;
        tasks.values().forEach(task -> task.cancelled = true);
        tasks.clear();
        asyncExecutor.shutdownNow();
        getPublicForkJoinPool().shutdownNow();
    }

    // Never fall back to executing world work on the caller when the server is absent.
    private MinecraftServer requireServer() {
        MinecraftServer server = serverSupplier.get();
        if (stopped || server == null || server.isStopped()) {
            throw new RejectedExecutionException("FAWE server is unavailable or stopping");
        }
        return server;
    }

    // Admission and shutdown share a lock so no delayed task can survive shutdown.
    private synchronized int schedule(Runnable runnable, int delay, int interval, boolean async) {
        requireServer();
        int id = nextId++;
        tasks.put(id, new ScheduledTask(runnable, tick + delay, interval, async));
        return id;
    }

    // Detach this tick's due list before running callbacks, which may schedule more work.
    private void tick(MinecraftServer server) {
        if (stopped || serverSupplier.get() != server) {
            return;
        }
        ArrayList<ScheduledTask> due = new ArrayList<>();
        synchronized (this) {
            tick++;
            for (ScheduledTask scheduled : tasks.values()) {
                if (scheduled.due <= tick && scheduled.running.compareAndSet(false, true)) {
                    scheduled.due = tick + Math.max(1, scheduled.interval);
                    due.add(scheduled);
                }
            }
        }
        for (ScheduledTask scheduled : due) {
            Runnable run = () -> {
                try {
                    if (!scheduled.cancelled && !stopped) {
                        scheduled.runnable.run();
                    }
                } finally {
                    synchronized (this) {
                        if (scheduled.interval == 0) {
                            tasks.values().remove(scheduled);
                        }
                        scheduled.running.set(false);
                    }
                }
            };
            if (scheduled.async) {
                async(run);
            } else {
                run.run();
            }
        }
    }

    // Mutable scheduling state is protected by the manager lock; cancellation crosses worker threads.
    private static final class ScheduledTask {
        private final Runnable runnable;
        private final int interval;
        private final boolean async;
        private final AtomicBoolean running = new AtomicBoolean();
        private long due;
        private volatile boolean cancelled;

        private ScheduledTask(Runnable runnable, long due, int interval, boolean async) {
            this.runnable = runnable;
            this.due = due;
            this.interval = interval;
            this.async = async;
        }
    }
}
