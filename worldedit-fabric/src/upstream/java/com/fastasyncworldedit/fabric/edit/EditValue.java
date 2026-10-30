package com.fastasyncworldedit.fabric.edit;

import java.util.Objects;

/**
 * A detached cell value or entity/portable-data payload.
 *
 * @param id complete block state, biome id, entity type or versioned data codec id
 * @param nbt full payload, or null for values without NBT
 */
public record EditValue(String id, FrozenNbt nbt) {
    public EditValue {
        Objects.requireNonNull(id);
        if (id.isBlank()) {
            throw new IllegalArgumentException("Value identifier must not be blank");
        }
    }

    /** Conservative retained-size estimate. */
    public long estimatedBytes() {
        return 96L + 2L * id.length() + (nbt == null ? 0 : nbt.estimatedBytes());
    }
}
