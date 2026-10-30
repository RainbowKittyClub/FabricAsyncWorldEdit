package com.fastasyncworldedit.fabric.edit;

import java.util.Objects;

/**
 * One typed proposed or actual delta, with world coordinates rather than chunk-local coordinates.
 *
 * @param kind mutation kind
 * @param x world x (biomes use their four-block cell origin)
 * @param y world y
 * @param z world z
 * @param key entity UUID or portable-data owner/codec key; empty for blocks and biomes
 * @param before old value, null only for absent entities or portable data
 * @param after new value, null only for removed entities or portable data
 */
public record EditDelta(Kind kind, int x, int y, int z, String key, EditValue before, EditValue after) {
    public EditDelta {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(key);
        if ((kind == Kind.BLOCK || kind == Kind.BIOME) && (before == null || after == null)) {
            throw new IllegalArgumentException("Block and biome deltas require both values");
        }
        if ((kind == Kind.ENTITY || kind == Kind.POSITION_DATA) && key.isBlank()) {
            throw new IllegalArgumentException("Entity and position-data deltas require an identity");
        }
    }

    /** Payload families with independent capability and authorization decisions. */
    public enum Kind {
        BLOCK, BIOME, ENTITY, POSITION_DATA
    }

    /** NBT-only changes are changes even if their state identifiers match. */
    public boolean isNoOp() {
        return Objects.equals(before, after);
    }

    /** Conservative retained-size estimate. */
    public long estimatedBytes() {
        return 128L + 2L * key.length() + (before == null ? 0 : before.estimatedBytes())
                + (after == null ? 0 : after.estimatedBytes());
    }
}
