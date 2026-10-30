package com.fastasyncworldedit.fabric.edit;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

/** Canonically ordered, detached NBT; callers never receive its mutable captured input. */
public final class FrozenNbt {
    private final String snbt;

    // All instances originate in a canonical encoding of parsed NBT.
    private FrozenNbt(String snbt) {
        this.snbt = snbt;
    }

    /** Capture a deep immutable value, including nested lists and primitive arrays. */
    public static FrozenNbt capture(CompoundTag tag) {
        return new FrozenNbt(canonical(Objects.requireNonNull(tag)));
    }

    /** Decode a fresh mutable copy for an owner-thread write. */
    public CompoundTag thaw() {
        try {
            return TagParser.parseCompoundFully(snbt);
        } catch (CommandSyntaxException exception) {
            throw new IllegalStateException("Invalid internally captured NBT", exception);
        }
    }

    /** Version-one canonical payload, suitable for audit and independent comparison. */
    public String encoded() {
        return snbt;
    }

    /** Conservative retained character storage, excluding referencing object overhead. */
    public long estimatedBytes() {
        return 64L + 2L * snbt.length();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FrozenNbt value && snbt.equals(value.snbt);
    }

    @Override
    public int hashCode() {
        return snbt.hashCode();
    }

    // Compound key ordering is not stable in ordinary SNBT output.
    private static String canonical(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            return compound.keySet().stream().sorted()
                    .map(key -> StringTag.quoteAndEscape(key) + ":" + canonical(compound.get(key)))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (tag instanceof ListTag list) {
            return IntStream.range(0, list.size()).mapToObj(index -> canonical(list.get(index)))
                    .collect(Collectors.joining(",", "[", "]"));
        }
        return tag.toString();
    }
}
