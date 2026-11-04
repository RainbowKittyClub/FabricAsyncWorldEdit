package com.fastasyncworldedit.fabric.edit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Versioned policy for mods that replace lighting or keep state on chunk sections. Section installs
 * run only beside versions whose code was inspected and tested; anything else refuses coordinated
 * edits with the reason. Each adapter is pinned to the version it was proven against, and reaches
 * that mod by reflection, so FAWE carries no compile dependency on it.
 */
public final class FabricOptimizations {
    private static final Map<String, String> VERIFIED = Map.of(
            "lithium", "0.26.2+mc26.3",
            "scalablelux", "0.3.0-alpha.0.6+26.3",
            "c2me", "0.4.2-alpha.0.106+26.3");
    // Other light engines: none has a verified light barrier.
    private static final List<String> UNVERIFIED_LIGHT_ENGINES = List.of("starlight", "phosphor", "moonrise");
    private static final long LIGHT_BARRIER_SECONDS = 60;

    private final String refusal;
    // ScalableLux: StarLightLightingProvider.scalablelux$getLightEngine(), StarLightInterface.scheduleChunkLight.
    private final Method starLight;
    private final Method scheduleChunkLight;
    // Lithium: LithiumSectionData.lithium$getSectionData(), SectionData.getChangeListener(),
    // ChunkSectionChangeCallback.onChunkSectionInvalidated(SectionPos).
    private final Method sectionData;
    private final Method changeListener;
    private final Method invalidated;

    private FabricOptimizations(String refusal, Method starLight, Method scheduleChunkLight, Method sectionData,
            Method changeListener, Method invalidated) {
        this.refusal = refusal;
        this.starLight = starLight;
        this.scheduleChunkLight = scheduleChunkLight;
        this.sectionData = sectionData;
        this.changeListener = changeListener;
        this.invalidated = invalidated;
    }

    /** Inspect the installed mods once; never throws, an unusable combination is a refusal. */
    public static FabricOptimizations detect() {
        List<String> problems = new ArrayList<>();
        var loader = FabricLoader.getInstance();
        for (var entry : VERIFIED.entrySet()) {
            loader.getModContainer(entry.getKey()).ifPresent(mod -> {
                String version = mod.getMetadata().getVersion().getFriendlyString();
                if (!version.equals(entry.getValue())) {
                    problems.add(entry.getKey() + " " + version + " is not verified (verified: " + entry.getValue() + ")");
                }
            });
        }
        // By the mod's own id: ScalableLux provides "starlight" as an alias.
        for (String id : UNVERIFIED_LIGHT_ENGINES) {
            if (loader.getModContainer(id).filter(mod -> mod.getMetadata().getId().equals(id)).isPresent()) {
                problems.add("light engine " + id + " has no verified light barrier");
            }
        }
        Method starLight = null;
        Method scheduleChunkLight = null;
        Method sectionData = null;
        Method changeListener = null;
        Method invalidated = null;
        try {
            if (loader.isModLoaded("scalablelux")) {
                starLight = Class.forName("ca.spottedleaf.starlight.common.light.StarLightLightingProvider")
                        .getMethod("scalablelux$getLightEngine");
                scheduleChunkLight = Class.forName("ca.spottedleaf.starlight.common.light.StarLightInterface")
                        .getMethod("scheduleChunkLight", ChunkPos.class, Runnable.class);
            }
            if (loader.isModLoaded("lithium")) {
                sectionData = Class.forName("net.caffeinemc.mods.lithium.common.world.section.LithiumSectionData")
                        .getMethod("lithium$getSectionData");
                changeListener = Class.forName("net.caffeinemc.mods.lithium.common.world.section.LithiumSectionData$SectionData")
                        .getMethod("getChangeListener");
                invalidated = Class.forName("net.caffeinemc.mods.lithium.common.tracking.block.ChunkSectionChangeCallback")
                        .getMethod("onChunkSectionInvalidated", SectionPos.class);
            }
        } catch (ReflectiveOperationException | LinkageError missing) {
            problems.add("a verified adapter no longer matches its mod: " + missing);
        }
        String refusal = problems.isEmpty() ? null
                : "Coordinated edits are disabled beside unverified optimization mods: " + String.join("; ", problems);
        return new FabricOptimizations(refusal, starLight, scheduleChunkLight, sectionData, changeListener, invalidated);
    }

    /** Null when coordinated edits may run, otherwise why not. */
    public String refusal() {
        return refusal;
    }

    /**
     * Completes once every light change queued for the chunk before this call has been processed.
     * Vanilla's own wait already is that barrier. ScalableLux's is not: it reads the chunk's queued
     * task, and a running task has already left the queue. Its scheduler never runs two tasks with
     * overlapping lock regions at once, so a marker that queues a second marker from inside its own
     * task resolves only after the task holding the changes has finished.
     */
    CompletableFuture<?> relightBarrier(ServerLevel level, int chunkX, int chunkZ) {
        var lightEngine = level.getChunkSource().getLightEngine();
        // Beside an unverified version only native writes run; they get vanilla's wait, unproven or not.
        if (starLight == null || refusal != null) {
            return lightEngine.waitForPendingTasks(chunkX, chunkZ);
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            Object engine = starLight.invoke(lightEngine);
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            Runnable second = () -> done.complete(null);
            Runnable first = () -> {
                try {
                    scheduleChunkLight.invoke(engine, pos, second);
                } catch (Throwable failure) {
                    done.completeExceptionally(failure);
                }
            };
            scheduleChunkLight.invoke(engine, pos, first);
            lightEngine.tryScheduleUpdate();
        } catch (InvocationTargetException failure) {
            done.completeExceptionally(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            done.completeExceptionally(failure);
        }
        return done.orTimeout(LIGHT_BARRIER_SECONDS, TimeUnit.SECONDS);
    }

    /** After a section object is replaced: Lithium trackers listening to the old one drop their caches. */
    void sectionReplaced(LevelChunkSection previous, SectionPos pos) {
        if (sectionData == null || refusal != null) {
            return;
        }
        try {
            Object listener = changeListener.invoke(sectionData.invoke(previous));
            if (listener != null) {
                invalidated.invoke(listener, pos);
            }
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("Lithium section invalidation failed", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Lithium section invalidation failed", failure);
        }
    }
}
