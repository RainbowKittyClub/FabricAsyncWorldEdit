package com.fastasyncworldedit.testharness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.fabric.FabricTaskManager;

/** Non-shipped observer for scheduler ownership, cancellation and shutdown rejection. */
public final class TaskLifecycleProbe implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> server.execute(() -> {
            FabricTaskManager manager = (FabricTaskManager) Fawe.platform().getTaskManager();
            int states = 0;
            for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
                for (var state : block.getStateDefinition().getPossibleStates()) {
                    var adapted = com.sk89q.worldedit.fabric.FabricAdapter.adapt(state);
                    require(com.sk89q.worldedit.fabric.FabricAdapter.adapt(adapted) == state,
                        "block-state roundtrip: " + state);
                    states++;
                }
            }
            require(states > 1000, "registry roundtrip sample count");
            require(com.sk89q.worldedit.world.generation.TreeType.REGISTRY.get("minecraft:oak_checked") != null,
                "placed tree registry");
            require(!com.fastasyncworldedit.core.configuration.Settings.settings().LIMITS.get("default").FAST_PLACEMENT,
                "fast placement must remain disabled");
            write("registry-states", Integer.toString(states));
            int start = server.getTickCount();
            server.tickRateManager().setTickRate(5);
            AtomicInteger repeats = new AtomicInteger();
            AtomicInteger asyncRuns = new AtomicInteger();
            int repeating = manager.repeat(() -> {
                require(server.isSameThread(), "repeat ownership");
                repeats.incrementAndGet();
            }, 2);
            int cancelled = manager.repeat(() -> {
                throw new AssertionError("Cancelled task executed");
            }, 1);
            manager.cancel(cancelled);
            manager.laterAsync(() -> {
                require(!server.isSameThread(), "async ownership");
                require(server.getTickCount() - start >= 3, "async tick delay");
                asyncRuns.incrementAndGet();
            }, 4);
            manager.later(() -> {
                require(server.isSameThread(), "delayed ownership");
                require(server.getTickCount() - start >= 7, "server tick delay");
                require(repeats.get() == 4, "repeat interval: " + repeats.get());
                manager.cancel(repeating);
                manager.later(() -> {
                    require(repeats.get() == 4, "repeat cancellation");
                    require(asyncRuns.get() == 1, "async one-shot count");
                    server.tickRateManager().setTickRate(20);
                    write("running", "passed");
                }, 10);
            }, 8);
        }));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            FabricTaskManager manager = (FabricTaskManager) Fawe.platform().getTaskManager();
            manager.shutdown();
            try {
                manager.task(() -> { throw new AssertionError("Work ran after stop"); });
                throw new AssertionError("Stopped scheduler accepted world work");
            } catch (RejectedExecutionException expected) {
                write("stopped", "passed");
            }
        });
    }

    // Assertions abort the rig; they never turn a failed scheduler observation into a skip.
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    // The caller supplies a fixture-local output prefix, not a production location.
    private static void write(String phase, String result) {
        try {
            Path resultPath = Path.of(System.getProperty("fawe.test.lifecycle") + "-" + phase + ".json");
            Path pendingPath = resultPath.resolveSibling(resultPath.getFileName() + ".pending");
            Files.writeString(pendingPath, "{\"phase\":\"" + phase + "\",\"status\":\"" + result + "\"}\n",
                    StandardOpenOption.CREATE_NEW);
            Files.move(pendingPath, resultPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
