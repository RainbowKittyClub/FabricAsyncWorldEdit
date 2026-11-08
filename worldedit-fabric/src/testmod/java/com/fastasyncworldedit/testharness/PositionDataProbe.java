package com.fastasyncworldedit.testharness;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import com.fastasyncworldedit.fabric.edit.FrozenNbt;
import com.fastasyncworldedit.fabric.edit.PositionDataRegistry;
import com.mojang.serialization.Codec;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

/** Owner-thread fixture operations independent of consumer implementation classes or relocation. */
final class PositionDataProbe {
    private static final AttachmentType<Boolean> UNKNOWN = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("fawe_s11", "unknown_position_owner"), builder -> builder.persistent(Codec.BOOL));

    private PositionDataProbe() { }

    static void initialize() { }

    static Object run(MinecraftServer server, String[] words) {
        var registry = PositionDataRegistry.get(server);
        if (words[1].equals("list")) return registry.versions();
        var level = server.overworld();
        var pos = new BlockPos(Integer.parseInt(words[2]), Integer.parseInt(words[3]), Integer.parseInt(words[4]));
        if (words[1].equals("unknown") || words[1].equals("clear-unknown")) {
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) throw new IllegalStateException("Fixture chunk is not loaded");
            if (words[1].equals("unknown")) ((AttachmentTarget) chunk).setAttached(UNKNOWN, true);
            else ((AttachmentTarget) chunk).removeAttached(UNKNOWN);
            return Map.of("unknown", words[1].equals("unknown"));
        }
        var state = level.getBlockState(pos);
        FrozenNbt before = registry.read(level, pos, state);
        if (words[1].equals("get")) return Map.of("payload", before == null ? "none" : before.encoded());
        try {
            if (words[1].equals("validate")) {
                CompoundTag malformed = TagParser.parseCompoundFully(String.join(" ", Arrays.copyOfRange(words, 5, words.length)));
                String failure = "";
                try {
                    registry.prepare(level, state, FrozenNbt.capture(malformed));
                } catch (RuntimeException refused) {
                    failure = refused.getMessage();
                }
                return Map.of("refused", !failure.isEmpty(), "failure", failure,
                        "unchanged", Objects.equals(before, registry.read(level, pos, state)));
            }
            if (before == null) throw new IllegalArgumentException("Fixture state holds no portable data");
            CompoundTag payload = before.thaw();
            String id = words[5];
            Integer version = registry.versions().get(id);
            if (version == null) throw new IllegalArgumentException("Unknown fixture owner " + id);
            CompoundTag entry = new CompoundTag();
            entry.putInt("version", version);
            if (words[1].equals("set")) {
                entry.put("value", TagParser.parseCompoundFully(String.join(" ", Arrays.copyOfRange(words, 6, words.length))));
            } else if (!words[1].equals("absent")) {
                throw new IllegalArgumentException("Unknown position-data fixture command");
            }
            payload.put(id, entry);
            registry.prepare(level, state, FrozenNbt.capture(payload)).accept(pos);
            // RCON fixture calls span ticks; publish the ordinary owner's data change explicitly.
            level.sendBlockUpdated(pos, state, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            return Map.of("payload", registry.read(level, pos, state).encoded());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException invalid) {
            throw new IllegalArgumentException(invalid);
        }
    }
}
