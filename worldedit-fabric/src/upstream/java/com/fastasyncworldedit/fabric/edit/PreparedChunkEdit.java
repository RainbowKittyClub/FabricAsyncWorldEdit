package com.fastasyncworldedit.fabric.edit;

import java.util.List;
import java.util.Objects;

/**
 * Detached publication unit. Read dependencies may extend beyond the destination chunk.
 *
 * @param context explicit operator and operation identity
 * @param chunkX destination chunk x
 * @param chunkZ destination chunk z
 * @param registryGeneration mapping generation that produced the proposal
 * @param reads exact observations used by masks, patterns and before-state capture
 * @param writes requested deltas; no-op proposals are counted, not published
 */
public record PreparedChunkEdit(EditActorContext context, int chunkX, int chunkZ,
        long registryGeneration, List<ReadDependency> reads, List<EditDelta> writes) {
    public PreparedChunkEdit {
        Objects.requireNonNull(context);
        reads = List.copyOf(reads);
        writes = List.copyOf(writes);
        if (writes.size() > 4096) {
            throw new IllegalArgumentException("A publication unit is limited to 4096 deltas");
        }
        for (EditDelta delta : writes) {
            if (delta.x() >> 4 != chunkX || delta.z() >> 4 != chunkZ) {
                throw new IllegalArgumentException("Write outside destination chunk");
            }
        }
    }

    /** Conservative accounting including lists, dependencies and payloads. */
    public long estimatedBytes() {
        long bytes = 256L + reads.size() * 128L + writes.size() * 16L;
        for (ReadDependency read : reads) {
            if (read.value() != null) {
                bytes = Math.addExact(bytes, read.value().estimatedBytes());
            }
        }
        for (EditDelta delta : writes) {
            bytes = Math.addExact(bytes, delta.estimatedBytes());
        }
        return bytes;
    }

    /**
     * An observed value and authoritative chunk mutation revision.
     *
     * @param kind observed payload family
     * @param x world x
     * @param y world y
     * @param z world z
     * @param key entity UUID or portable-data codec key
     * @param value observed value, including absence
     * @param revision chunk revision, including observed ABA mutations
     */
    public record ReadDependency(EditDelta.Kind kind, int x, int y, int z, String key,
            EditValue value, long revision) {
        public ReadDependency {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(key);
        }
    }
}
