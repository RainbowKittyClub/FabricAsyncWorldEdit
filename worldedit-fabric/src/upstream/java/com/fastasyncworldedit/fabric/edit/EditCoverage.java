package com.fastasyncworldedit.fabric.edit;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Units that reached each stage of the mutation path, by route and source: {@code native} cells
 * from WorldEdit sessions and {@code bulk} units from the coordinated API, so every source can be
 * shown to pass every stage. Bulk adds {@code ticket}; a native cell reads an already loaded chunk.
 */
public final class EditCoverage {
    private static final Map<String, LongAdder> REACHED = new ConcurrentHashMap<>();

    private EditCoverage() {
    }

    static void reached(boolean nativeRoute, EditActorContext.Source source, String stage) {
        REACHED.computeIfAbsent((nativeRoute ? "native." : "bulk.") + source + "." + stage, key -> new LongAdder())
                .increment();
    }

    /** Counts keyed {@code <route>.<source>.<stage>}, sorted. */
    public static Map<String, Long> snapshot() {
        Map<String, Long> counts = new TreeMap<>();
        REACHED.forEach((key, count) -> counts.put(key, count.sum()));
        return counts;
    }
}
