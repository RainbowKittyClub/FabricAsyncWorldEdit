package com.fastasyncworldedit.testharness;

import java.util.HashMap;
import java.util.Map;

import com.fastasyncworldedit.fabric.edit.FabricOptimizations;
import com.google.gson.Gson;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;

/** `fawes9`: light, POI, path-cache and policy probes for the S9 lane, all read on the server thread. */
public final class OptimizationProbe implements ModInitializer {
    private static final Gson GSON = new Gson();
    private static final String HEX = "0123456789abcdef";

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("fawes9").then(Commands.argument("arguments", StringArgumentType.greedyString())
                        .executes(command -> {
                            String answer;
                            try {
                                answer = run(command.getSource().getLevel(),
                                        StringArgumentType.getString(command, "arguments").split(" "));
                            } catch (RuntimeException failure) {
                                answer = GSON.toJson(Map.of("error", String.valueOf(failure)));
                            }
                            String text = answer;
                            command.getSource().sendSuccess(() -> Component.literal(text), false);
                            return 1;
                        }))));
    }

    private static String run(ServerLevel level, String[] words) {
        switch (words[0]) {
            // light x1 y1 z1 x2 y2 z2: one hex digit per cell, x fastest, then z, then y.
            case "light" -> {
                int[] box = new int[6];
                for (int index = 0; index < 6; index++) box[index] = Integer.parseInt(words[index + 1]);
                StringBuilder block = new StringBuilder();
                StringBuilder sky = new StringBuilder();
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                for (int y = box[1]; y <= box[4]; y++) for (int z = box[2]; z <= box[5]; z++) for (int x = box[0]; x <= box[3]; x++) {
                    pos.set(x, y, z);
                    block.append(HEX.charAt(level.getBrightness(LightLayer.BLOCK, pos)));
                    sky.append(HEX.charAt(level.getBrightness(LightLayer.SKY, pos)));
                }
                return GSON.toJson(Map.of("block", block.toString(), "sky", sky.toString()));
            }
            // count x1 y1 z1 x2 y2 z2 block: cells holding that block.
            case "count" -> {
                int count = 0;
                for (BlockPos pos : BlockPos.betweenClosed(Integer.parseInt(words[1]), Integer.parseInt(words[2]),
                        Integer.parseInt(words[3]), Integer.parseInt(words[4]), Integer.parseInt(words[5]),
                        Integer.parseInt(words[6]))) {
                    if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock())
                            .toString().equals(words[7])) count++;
                }
                return GSON.toJson(Map.of("count", count));
            }
            case "poi" -> {
                var type = level.getPoiManager().getType(pos(words));
                Map<String, Object> answer = new HashMap<>();
                answer.put("poi", type.flatMap(holder -> holder.unwrapKey()).map(key -> key.identifier().toString()).orElse(null));
                return GSON.toJson(answer);
            }
            // The cached path type: a stale cache keeps the value from before an edit.
            case "pathtype" -> {
                return GSON.toJson(Map.of("type", level.getPathTypeCache().getOrCompute(level, pos(words)).name()));
            }
            case "optimizations" -> {
                Map<String, Object> answer = new HashMap<>();
                answer.put("refusal", FabricOptimizations.detect().refusal());
                return GSON.toJson(answer);
            }
            default -> throw new IllegalArgumentException("Unknown fawes9 command: " + words[0]);
        }
    }

    private static BlockPos pos(String[] words) {
        return new BlockPos(Integer.parseInt(words[1]), Integer.parseInt(words[2]), Integer.parseInt(words[3]));
    }
}
