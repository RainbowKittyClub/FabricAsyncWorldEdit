package com.fastasyncworldedit.testharness;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;

import net.minecraft.world.level.Level;

/**
 * S11 write oracle, independent of FAWE's own counters: every chunk block, block-entity and entity
 * write is classified by the code on its stack. A FAWE/WorldEdit frame without coordinator
 * publication is unguarded; neither is gameplay, counted only inside the watched box.
 */
public final class S11Writes {
    private static final String COORDINATOR = "com.fastasyncworldedit.fabric.edit.FabricCommitCoordinator";
    private static final int SAMPLES = 32;
    private static final Map<String, LongAdder> COUNTS = new ConcurrentHashMap<>();
    private static final List<String> SAMPLED = new CopyOnWriteArrayList<>();
    private static volatile int[] box;

    private S11Writes() {
    }

    static void watch(int[] watched) {
        box = null;
        COUNTS.clear();
        SAMPLED.clear();
        box = watched;
    }

    static void stop() {
        box = null;
    }

    static Map<String, Object> report() {
        Map<String, Long> counts = new TreeMap<>();
        COUNTS.forEach((key, count) -> counts.put(key, count.sum()));
        return Map.of("counts", counts, "samples", List.copyOf(SAMPLED));
    }

    /** Called from the oracle mixins at the head of each write. */
    public static void observe(String kind, Level level, int x, int y, int z) {
        int[] watched = box;
        if (watched == null || level.isClientSide()) {
            return;
        }
        boolean inside = x >= watched[0] && y >= watched[1] && z >= watched[2]
                && x <= watched[3] && y <= watched[4] && z <= watched[5];
        boolean serverThread = level.getServer() != null && level.getServer().isSameThread();
        String origin = origin();
        if (origin.equals("gameplay") && !inside && serverThread) {
            return;
        }
        String key = kind + "." + origin + (inside ? ".inside" : ".outside") + (serverThread ? "" : ".offthread");
        COUNTS.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        if ((origin.equals("unguarded") || !serverThread) && SAMPLED.size() < SAMPLES) {
            SAMPLED.add(key + " " + x + "," + y + "," + z + " via " + firstEditFrame());
        }
    }

    private static String origin() {
        return StackWalker.getInstance().walk(frames -> {
            boolean edit = false;
            for (var iterator = frames.iterator(); iterator.hasNext();) {
                String name = iterator.next().getClassName();
                if (name.equals(COORDINATOR)) {
                    return "guarded";
                }
                edit |= isEdit(name);
            }
            return edit ? "unguarded" : "gameplay";
        });
    }

    private static String firstEditFrame() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> isEdit(frame.getClassName()))
                .findFirst()
                .map(frame -> frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber())
                .orElse("?"));
    }

    private static boolean isEdit(String name) {
        return (name.startsWith("com.sk89q.worldedit") || name.startsWith("com.fastasyncworldedit"))
                && !name.startsWith("com.fastasyncworldedit.testharness");
    }
}
