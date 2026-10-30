package com.fastasyncworldedit.fabric.edit;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.world.block.BlockState;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;

/** A complete, immutable registry-generation mapping; numeric Minecraft IDs are never FAWE ordinals. */
public final class FabricStateMapping {
    private static final AtomicLong GENERATIONS = new AtomicLong();
    private final long generation = GENERATIONS.incrementAndGet();
    private final BlockState[] states = new BlockState[Character.MAX_VALUE + 1];
    private final Map<String, Character> ordinals;
    private final Map<String, net.minecraft.world.level.block.state.BlockState> nativeStates;

    /** Snapshot the loaded registry on its owner thread and verify every roundtrip. */
    public FabricStateMapping(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("State mapping must be captured on the server thread");
        }
        Map<String, Character> mapped = new HashMap<>();
        Map<String, net.minecraft.world.level.block.state.BlockState> natives = new HashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            for (var nativeState : block.getStateDefinition().getPossibleStates()) {
                BlockState state = FabricAdapter.adapt(nativeState);
                int ordinal = state.getOrdinal();
                if (ordinal == 0 || ordinal > Character.MAX_VALUE
                        || (states[ordinal] != null && !states[ordinal].equals(state))
                        || FabricAdapter.adapt(state) != nativeState) {
                    throw new IllegalStateException("Non-bijective state mapping: " + nativeState);
                }
                states[ordinal] = state;
                mapped.put(state.getAsString(), (char) ordinal);
                natives.put(state.getAsString(), nativeState);
            }
        }
        ordinals = Map.copyOf(mapped);
        nativeStates = Map.copyOf(natives);
    }

    /** Unique generation, fenced at every publication. */
    public long generation() {
        return generation;
    }

    /** Translate a full identifier/property value without a mutable registry lookup. */
    public char ordinal(String state) {
        Character ordinal = ordinals.get(state);
        if (ordinal == null) {
            throw new IllegalArgumentException("Unknown mapped state: " + state);
        }
        return ordinal;
    }

    /** Immutable FAWE state for a detached filter read. */
    public BlockState state(char ordinal) {
        BlockState state = states[ordinal];
        if (state == null) {
            throw new IllegalArgumentException("Unknown ordinal: " + (int) ordinal);
        }
        return state;
    }

    /** Owner-thread native state lookup; never exposes a live registry view. */
    public net.minecraft.world.level.block.state.BlockState nativeState(String state) {
        var result = nativeStates.get(state);
        if (result == null) {
            throw new IllegalArgumentException("Unknown mapped state: " + state);
        }
        return result;
    }

    /** Number of mapped states, including modded properties. */
    public int size() {
        return ordinals.size();
    }
}
