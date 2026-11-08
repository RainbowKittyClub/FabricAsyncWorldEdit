package com.fastasyncworldedit.fabric.edit;

import java.util.Set;

import net.minecraft.nbt.CompoundTag;

/** Carries portable data through core's existing full-block NBT, never into a live block entity. */
public final class PositionDataTransport {
    private static final String KEY = "fawe:position_data";
    private static final String SYNTHETIC_ID = "fawe:position_data";
    private static final Set<String> SYNTHETIC_FIELDS = Set.of("id", KEY, "x", "y", "z");

    private PositionDataTransport() { }

    /** Combine detached owner data with real NBT, or synthesize a core-only tile for a plain block. */
    public static FrozenNbt combine(CompoundTag blockEntity, FrozenNbt positions) {
        if (blockEntity != null && blockEntity.contains(KEY)) {
            throw new IllegalArgumentException("Block-entity NBT uses reserved key " + KEY);
        }
        if (positions == null) return blockEntity == null ? null : FrozenNbt.capture(blockEntity);
        CompoundTag combined = blockEntity == null ? new CompoundTag() : blockEntity.copy();
        if (blockEntity == null) combined.putString("id", SYNTHETIC_ID);
        combined.put(KEY, positions.thaw());
        return FrozenNbt.capture(combined);
    }

    /** Extract the exact versioned owner map; malformed envelopes are never silently discarded. */
    public static FrozenNbt positions(FrozenNbt frozen) {
        if (frozen == null) return null;
        CompoundTag tag = frozen.thaw();
        if (!tag.contains(KEY)) return null;
        if (!(tag.get(KEY) instanceof CompoundTag positions)) {
            throw new IllegalArgumentException("Malformed portable position data");
        }
        return FrozenNbt.capture(positions);
    }

    /** Whether a full block carries an actual owner value rather than explicit absence alone. */
    public static boolean valuesPresent(FrozenNbt frozen) {
        FrozenNbt positions = positions(frozen);
        if (positions == null) return false;
        CompoundTag payload = positions.thaw();
        for (String id : payload.keySet()) {
            if (!(payload.get(id) instanceof CompoundTag entry)) {
                throw new IllegalArgumentException("Malformed position-data owner " + id);
            }
            if (entry.contains("value")) return true;
        }
        return false;
    }

    /** Strip core-only data before vanilla validates or loads a real block-entity payload. */
    public static CompoundTag blockEntity(FrozenNbt frozen) {
        if (frozen == null) return null;
        CompoundTag tag = frozen.thaw();
        if (SYNTHETIC_ID.equals(tag.getStringOr("id", ""))) {
            if (!tag.contains(KEY) || !SYNTHETIC_FIELDS.containsAll(tag.keySet())) {
                throw new IllegalArgumentException("Malformed synthetic position-data tile");
            }
            positions(frozen);
            return null;
        }
        positions(frozen);
        tag.remove(KEY);
        return tag;
    }
}
