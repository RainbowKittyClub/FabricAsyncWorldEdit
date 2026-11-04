package com.fastasyncworldedit.testharness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fastasyncworldedit.core.history.DiskStorageHistory;
import com.fastasyncworldedit.fabric.edit.CommitReceipt;
import com.fastasyncworldedit.fabric.edit.NativeHistoryExtent;
import com.google.gson.Gson;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.fabric.FabricAdapter;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** `fawes10`: session history and observed native receipts for the S10 lanes. */
public final class HistoryProbe implements ModInitializer {
    private static final Gson GSON = new Gson();
    private static final List<CommitReceipt> RECEIPTS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Called by QueueContractProbe for every receipt outside its own fixtures. */
    static void observe(CommitReceipt receipt) {
        if (!receipt.applied().isEmpty() || receipt.denied() != 0) {
            RECEIPTS.add(receipt);
        }
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("fawes10").then(Commands.argument("arguments", StringArgumentType.greedyString())
                        .executes(command -> {
                            String answer;
                            try {
                                answer = run(command.getSource().getServer(),
                                        StringArgumentType.getString(command, "arguments").split(" "));
                            } catch (RuntimeException failure) {
                                answer = GSON.toJson(Map.of("error", String.valueOf(failure)));
                            }
                            String text = answer;
                            command.getSource().sendSuccess(() -> Component.literal(text), false);
                            return 1;
                        }))));
    }

    private static String run(MinecraftServer server, String[] words) {
        switch (words[0]) {
            // history <player>: entries oldest first, with the provenance undo would bind.
            case "history" -> {
                var player = server.getPlayerList().getPlayerByName(words[1]);
                if (player == null) throw new IllegalArgumentException("No such player " + words[1]);
                var actor = FabricAdapter.adaptPlayer(player);
                var session = WorldEdit.getInstance().getSessionManager().get(actor);
                session.loadSessionHistoryFromDisk(actor.getUniqueId(), actor.getWorld());
                List<Map<String, Object>> entries = new ArrayList<>();
                for (var changeSet : session.getHistory()) {
                    var origin = NativeHistoryExtent.originOf(changeSet);
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("type", changeSet.getClass().getSimpleName());
                    entry.put("index", changeSet instanceof DiskStorageHistory disk ? disk.getIndex() : -1);
                    entry.put("operation", String.valueOf(origin.operationId()));
                    entry.put("actor", String.valueOf(origin.actorId()));
                    entry.put("size", changeSet.longSize());
                    entries.add(entry);
                }
                return GSON.toJson(Map.of("negative", session.getHistoryNegativeIndex(), "entries", entries));
            }
            // biome x y z: the stored overworld cell, without BiomeManager's zoom.
            case "biome" -> {
                var biome = server.overworld().getNoiseBiome(Integer.parseInt(words[1]) >> 2,
                        Integer.parseInt(words[2]) >> 2, Integer.parseInt(words[3]) >> 2);
                return GSON.toJson(Map.of("biome", biome.unwrapKey().orElseThrow().identifier().toString()));
            }
            case "receipts" -> {
                if (words.length > 1 && words[1].equals("clear")) {
                    RECEIPTS.clear();
                    return GSON.toJson(Map.of("cleared", true));
                }
                List<Map<String, Object>> receipts = new ArrayList<>();
                for (CommitReceipt receipt : RECEIPTS) {
                    var context = receipt.context();
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("source", context.source().name());
                    entry.put("session", String.valueOf(context.sessionId()));
                    entry.put("parent", String.valueOf(context.parentId()));
                    entry.put("originalActor", String.valueOf(context.originalActorId()));
                    entry.put("actor", String.valueOf(context.actorId()));
                    entry.put("applied", receipt.applied().size());
                    entry.put("denied", receipt.denied());
                    entry.put("outcome", receipt.outcome().name());
                    receipts.add(entry);
                }
                return GSON.toJson(Map.of("receipts", receipts));
            }
            default -> throw new IllegalArgumentException("Unknown fawes10 command: " + words[0]);
        }
    }
}
