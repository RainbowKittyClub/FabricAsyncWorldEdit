package com.fastasyncworldedit.testharness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fastasyncworldedit.fabric.edit.EditActorContext;
import com.fastasyncworldedit.fabric.edit.EditCoverage;
import com.fastasyncworldedit.fabric.edit.FabricEdits;
import com.google.gson.Gson;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.history.changeset.ArrayListHistory;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * `fawes11`: the S11 write oracle, stage coverage, box dumps and a public-API consumer. The
 * consumer edits as a third-party mod would; it never writes the world itself.
 */
public final class S11Probe implements ModInitializer {
    private static final Gson GSON = new Gson();
    // A plain worker thread: no WorldEdit command or tool request is ever live on it.
    private static final ExecutorService API_THREAD = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fawe-s11-api-consumer");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, Map<String, Object>> RESULTS = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_SERVER_TICK.register(server -> {
            String deop = DEOP;
            if (deop != null && FabricEdits.nativeService(server).publicationTimeline().stream()
                    .anyMatch(publication -> publication.tick() > DEOP_AFTER)) {
                DEOP = null;
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "deop " + deop);
            }
            BlockPos change = CHANGE_AT;
            if (change != null && EditCoverage.snapshot().getOrDefault("native.PLAYER.admitted", 0L) > CHANGE_AFTER) {
                CHANGE_AT = null;
                var level = server.overworld();
                level.setBlock(change, level.getBlockState(change).is(net.minecraft.world.level.block.Blocks.STONE)
                        ? net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState()
                        : net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
            }
            BlockPos toggle = TOGGLE;
            if (toggle != null) {
                var level = server.overworld();
                level.setBlock(toggle, level.getBlockState(toggle).is(net.minecraft.world.level.block.Blocks.STONE)
                        ? net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState()
                        : net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("fawes11").then(Commands.argument("arguments", StringArgumentType.greedyString())
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
            // watch x1 y1 z1 x2 y2 z2: reset the oracle and count from now; gameplay counts only inside.
            case "watch" -> {
                S11Writes.watch(box(words, 1));
                return GSON.toJson(Map.of("watching", true));
            }
            case "stop" -> {
                S11Writes.stop();
                return GSON.toJson(Map.of("watching", false));
            }
            case "writes" -> {
                return GSON.toJson(S11Writes.report());
            }
            case "coverage" -> {
                return GSON.toJson(EditCoverage.snapshot());
            }
            // selection <player>: the session selector's world and region bounds, as WorldEdit sees them.
            case "selection" -> {
                ServerPlayer player = server.getPlayerList().getPlayerByName(words[1]);
                if (player == null) throw new IllegalArgumentException("No such player " + words[1]);
                var actor = FabricAdapter.adaptPlayer(player);
                var session = WorldEdit.getInstance().getSessionManager().get(actor);
                // The selector's own world: asking with the player's world would reset a mismatched selector.
                var world = session.getSelectionWorld();
                Map<String, Object> answer = new java.util.LinkedHashMap<>();
                answer.put("playerWorld", actor.getWorld().id());
                answer.put("selectorWorld", world == null ? null : world.id() + " " + world.getClass().getSimpleName());
                answer.put("worldMinY", world == null ? null : world.getMinY());
                answer.put("worldMaxY", world == null ? null : world.getMaxY());
                try {
                    var region = session.getSelection();
                    answer.put("min", region.getMinimumPoint().toString());
                    answer.put("max", region.getMaximumPoint().toString());
                    answer.put("volume", region.getVolume());
                } catch (com.sk89q.worldedit.IncompleteRegionException incomplete) {
                    answer.put("region", "incomplete");
                }
                return GSON.toJson(answer);
            }
            case "timing" -> {
                return GSON.toJson(FabricEdits.nativeService(server).publicationTiming());
            }
            // timeline since: publications after that server tick, oldest first.
            case "timeline" -> {
                int since = Integer.parseInt(words[1]);
                return GSON.toJson(Map.of("tick", server.getTickCount(), "publications",
                        FabricEdits.nativeService(server).publicationTimeline().stream()
                                .filter(publication -> publication.tick() > since).toList()));
            }
            // toggle x y z | toggle off: flip one block every tick, before the coordinator's heartbeat.
            case "toggle" -> {
                TOGGLE = words[1].equals("off") ? null
                        : new BlockPos(Integer.parseInt(words[1]), Integer.parseInt(words[2]), Integer.parseInt(words[3]));
                return GSON.toJson(Map.of("toggle", String.valueOf(TOGGLE)));
            }
            // change-on-admit x y z: flip that cell once, at the start of the tick after the next
            // native unit is admitted, so between its snapshot and its publication at the tick's end.
            case "change-on-admit" -> {
                CHANGE_AFTER = EditCoverage.snapshot().getOrDefault("native.PLAYER.admitted", 0L);
                CHANGE_AT = new BlockPos(Integer.parseInt(words[1]), Integer.parseInt(words[2]), Integer.parseInt(words[3]));
                return GSON.toJson(Map.of("armed", CHANGE_AT.toShortString(), "after", CHANGE_AFTER));
            }
            // deop-on-publish player: de-op them on the tick after the next publication.
            case "deop-on-publish" -> {
                DEOP_AFTER = server.getTickCount();
                DEOP = words[1];
                return GSON.toJson(Map.of("armed", DEOP, "after", DEOP_AFTER));
            }
            // ryw player x y z: an API session reads its own write before it is flushed.
            case "ryw" -> {
                ServerPlayer player = server.getPlayerList().getPlayerByName(words[1]);
                if (player == null) throw new IllegalArgumentException("No such player " + words[1]);
                var position = BlockVector3.at(Integer.parseInt(words[2]), Integer.parseInt(words[3]),
                        Integer.parseInt(words[4]));
                String id = UUID.randomUUID().toString();
                Map<String, Object> result = new ConcurrentHashMap<>(Map.of("done", false));
                RESULTS.put(id, result);
                API_THREAD.execute(() -> readYourWrites(server, player, position, result));
                return GSON.toJson(Map.of("id", id));
            }
            case "statistics" -> {
                return GSON.toJson(FabricEdits.nativeService(server).publicationStatistics());
            }
            // dump x1 y1 z1 x2 y2 z2 [dimension]: states, y then z then x ascending, as [state, run] pairs.
            case "dump" -> {
                int[] box = box(words, 1);
                var level = words.length > 7 ? server.getLevel(net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.Identifier.parse(words[7])))
                        : server.overworld();
                if (level == null) throw new IllegalArgumentException("No such dimension " + words[7]);
                List<Object[]> runs = new ArrayList<>();
                for (int y = box[1]; y <= box[4]; y++) {
                    for (int z = box[2]; z <= box[5]; z++) {
                        for (int x = box[0]; x <= box[3]; x++) {
                            String state = FabricAdapter.adapt(level.getBlockState(new BlockPos(x, y, z))).getAsString();
                            Object[] last = runs.isEmpty() ? null : runs.getLast();
                            if (last != null && last[0].equals(state)) {
                                last[1] = (Integer) last[1] + 1;
                            } else {
                                runs.add(new Object[] {state, 1});
                            }
                        }
                    }
                }
                return GSON.toJson(Map.of("runs", runs));
            }
            // api <native|bulk> <player> <block> x1 y1 z1 x2 y2 z2: start an API edit as that player.
            case "api" -> {
                if (words[1].equals("result")) {
                    var result = RESULTS.get(words[2]);
                    return GSON.toJson(result == null ? Map.of("error", "no such edit") : result);
                }
                ServerPlayer player = server.getPlayerList().getPlayerByName(words[2]);
                if (player == null) throw new IllegalArgumentException("No such player " + words[2]);
                BlockState block = BlockTypes.get(words[3]).getDefaultState();
                int[] box = box(words, 4);
                String id = UUID.randomUUID().toString();
                Map<String, Object> result = new ConcurrentHashMap<>(Map.of("done", false));
                RESULTS.put(id, result);
                switch (words[1]) {
                    case "native" -> API_THREAD.execute(() -> nativeEdit(server, player, block, box, result));
                    case "bulk" -> bulkEdit(server, player, block, box, result);
                    default -> throw new IllegalArgumentException("Unknown API route " + words[1]);
                }
                return GSON.toJson(Map.of("id", id));
            }
            default -> throw new IllegalArgumentException("Unknown fawes11 command " + words[0]);
        }
    }

    // WorldEdit's public session API, off the server thread, with the player as actor.
    private static void nativeEdit(MinecraftServer server, ServerPlayer player, BlockState block, int[] box,
            Map<String, Object> result) {
        try {
            var world = FabricAdapter.adapt(server.overworld());
            var region = new CuboidRegion(world, BlockVector3.at(box[0], box[1], box[2]), BlockVector3.at(box[3], box[4], box[5]));
            try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(world)
                    .actor(FabricAdapter.adaptPlayer(player)).build()) {
                result.put("changed", session.setBlocks((com.sk89q.worldedit.regions.Region) region, block));
            }
        } catch (Throwable failure) {
            result.put("error", String.valueOf(failure));
        } finally {
            result.put("done", true);
        }
    }

    // One unflushed write: the session reads it back while the world still holds the old block.
    private static void readYourWrites(MinecraftServer server, ServerPlayer player, BlockVector3 position,
            Map<String, Object> result) {
        try {
            var world = FabricAdapter.adapt(server.overworld());
            var pos = new BlockPos(position.x(), position.y(), position.z());
            try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(world)
                    .actor(FabricAdapter.adaptPlayer(player)).build()) {
                session.setBlock(position, BlockTypes.DIAMOND_BLOCK.getDefaultState());
                result.put("session", session.getBlock(position).getAsString());
                result.put("sessionFull", session.getFullBlock(position).toImmutableState().getAsString());
                result.put("world", server.submit(() -> FabricAdapter.adapt(server.overworld().getBlockState(pos))
                        .getAsString()).join());
            }
            result.put("flushed", server.submit(() -> FabricAdapter.adapt(server.overworld().getBlockState(pos))
                    .getAsString()).join());
        } catch (Throwable failure) {
            result.put("error", String.valueOf(failure));
        } finally {
            result.put("done", true);
        }
    }

    private static volatile BlockPos TOGGLE;
    private static volatile String DEOP;
    private static volatile int DEOP_AFTER;
    private static volatile BlockPos CHANGE_AT;
    private static volatile long CHANGE_AFTER;

    // FAWE's asynchronous coordinated API, submitted on the owner thread.
    private static void bulkEdit(MinecraftServer server, ServerPlayer player, BlockState block, int[] box,
            Map<String, Object> result) {
        var context = new EditActorContext(player.getUUID(), player.getGameProfile().name(), EditActorContext.Source.API,
                UUID.randomUUID(), null, null, "minecraft:overworld", SideEffectSet.none(), new EditActorContext.Cancellation());
        List<BlockVector2> chunks = new ArrayList<>();
        for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) {
            for (int cz = box[2] >> 4; cz <= box[5] >> 4; cz++) {
                chunks.add(BlockVector2.at(cx, cz));
            }
        }
        FabricEdits.get(server).submitIndependent(context, chunks, new ArrayListHistory(), queue -> {
            for (int y = box[1]; y <= box[4]; y++) {
                for (int z = box[2]; z <= box[5]; z++) {
                    for (int x = box[0]; x <= box[3]; x++) {
                        queue.setBlock(x, y, z, block);
                    }
                }
            }
        }).whenComplete((receipts, failure) -> {
            if (receipts != null) {
                result.put("changed", receipts.stream().mapToInt(receipt -> receipt.applied().size()).sum());
            }
            if (failure != null) result.put("error", String.valueOf(failure));
            result.put("done", true);
        });
    }

    private static int[] box(String[] words, int from) {
        int[] box = new int[6];
        for (int i = 0; i < 3; i++) {
            int a = Integer.parseInt(words[from + i]);
            int b = Integer.parseInt(words[from + 3 + i]);
            box[i] = Math.min(a, b);
            box[i + 3] = Math.max(a, b);
        }
        return box;
    }
}
