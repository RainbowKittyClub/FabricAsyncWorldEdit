package com.fastasyncworldedit.testharness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import com.fastasyncworldedit.fabric.compat.EditIntegration;
import com.fastasyncworldedit.fabric.compat.EditIntegrations;
import com.fastasyncworldedit.fabric.edit.CommitReceipt;
import com.fastasyncworldedit.fabric.edit.EditActorContext;
import com.fastasyncworldedit.fabric.edit.EditDelta;
import com.fastasyncworldedit.fabric.edit.FabricEdits;
import com.fastasyncworldedit.fabric.edit.PreparedChunkEdit;
import com.google.gson.Gson;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.sk89q.worldedit.history.changeset.ArrayListHistory;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/** Independent section/metadata oracle around the production queue API. */
public final class PublicationProbe implements ModInitializer {
    private static final Map<UUID, Run> RUNS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final List<Run> cases = new ArrayList<>();
    private static final List<Runnable> tickets = new ArrayList<>();
    private static Run current;
    private static FabricEdits lastService;
    private static CompletableFuture<Void> commandGate = CompletableFuture.completedFuture(null);
    private static CompletableFuture<?> fixture = CompletableFuture.completedFuture(null);
    private static int nextChunk = 80;
    private static final int Z = 64;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("fawepublish").then(Commands.argument("arguments", StringArgumentType.greedyString())
                        .executes(command -> {
                            try {
                                String answer = control(command.getSource().getServer(), StringArgumentType.getString(command, "arguments"));
                                command.getSource().sendSuccess(() -> Component.literal(answer), false);
                                return 1;
                            } catch (Throwable failure) {
                                command.getSource().sendFailure(Component.literal(failure.toString()));
                                return 0;
                            }
                        }))));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (expectedPublishedShutdown()) {
                current.sendGate.complete(null);
                tickets.forEach(Runnable::run);
                tickets.clear();
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (!expectedPublishedShutdown()) return;
            try {
                Throwable failure = lastService.shutdownDrain().handle((unused, problem) -> problem)
                        .toCompletableFuture().get(15, java.util.concurrent.TimeUnit.SECONDS);
                require(failure != null, "Interrupted required publication must fail its drain");
                require(current.future.isCompletedExceptionally(), "Published stop fails completion");
                int applied = current.receipts.stream().mapToInt(receipt -> receipt.applied().size()).sum();
                require(applied == 4096 && current.history.size() == 4096, "Shutdown retained actual history");
                require(current.closed.get() == 1, "Shutdown released reservation once");
                require(lastService.preparationStatistics().get("tickets") == 0, "Shutdown released tickets");
                java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("fawe.test.lifecycle") + "-s5-stop.json"),
                        new Gson().toJson(Map.of("cx", current.cx, "applied", applied, "history", current.history.size(),
                                "closed", current.closed.get(), "drainFailure", failure.toString())));
            } catch (Exception failure) {
                throw new IllegalStateException("Published shutdown evidence failed", failure);
            }
        });
    }

    public static boolean expectedPublishedShutdown() {
        return current != null && current.kind.equals("stop-published") && current.published.get() == 1;
    }

    public static boolean owns(PreparedChunkEdit proposal) {
        return RUNS.containsKey(proposal.context().operationId())
                || proposal.context().source() == EditActorContext.Source.PLAYER;
    }

    public static CompletionStage<EditIntegration.Reservation> prepare(PreparedChunkEdit proposal) {
        Run run = RUNS.computeIfAbsent(proposal.context().operationId(), id -> {
            require(proposal.context().source() == EditActorContext.Source.PLAYER, "Unexpected probe identity");
            Run command = new Run(proposal.chunkX(), "command");
            commandGate.thenRun(() -> command.gate.complete(null));
            return command;
        });
        int attempt = run.prepared.incrementAndGet();
        return (attempt == 1 ? run.gate : CompletableFuture.<Void>completedFuture(null)).thenApply(unused -> new EditIntegration.Reservation() {
            @Override
            public boolean authorize(ServerLevel level, ServerPlayer player, EditActorContext actor, EditDelta delta) {
                require(level.getServer().isSameThread(), "Authorization is owner-thread");
                return true;
            }
            @Override
            public void beforePublication(ServerLevel level, List<EditDelta> deltas) { }
            @Override
            public CompletionStage<Void> reconcile(ServerLevel level, CommitReceipt receipt) {
                return run.kind.equals("finalization-failure")
                        ? CompletableFuture.failedFuture(new IllegalStateException("S5 injected reconciliation failure"))
                        : CompletableFuture.completedFuture(null);
            }
            @Override
            public CompletionStage<Void> publish(ServerLevel level, CommitReceipt receipt) {
                require(level.getServer().isSameThread(), "Watcher publication is owner-thread");
                run.published.incrementAndGet();
                if (run.kind.equals("send-failure")) return CompletableFuture.failedFuture(new IllegalStateException("S5 injected send failure"));
                return run.kind.equals("send-held") || run.kind.equals("stop-published")
                        ? run.sendGate : CompletableFuture.completedFuture(null);
            }
            @Override
            public CompletionStage<Void> record(CommitReceipt receipt) {
                if (run.kind.equals("command")) {
                    for (var applied : receipt.applied()) {
                        var delta = applied.delta();
                        if (delta.x() >= 1104 && delta.x() <= 1135 && delta.after().id().equals("minecraft:diamond_block")) {
                            require(delta.before().id().equals("minecraft:gold_block"), "Overlapping command recorded stale before-state");
                        }
                    }
                }
                run.recorded.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
            @Override
            public void close(ServerLevel level) { run.closed.incrementAndGet(); }
            @Override
            public void discard() { run.closed.incrementAndGet(); }
        });
    }

    private static String control(MinecraftServer server, String args) throws Exception {
        String[] words = args.split(" ");
        ServerLevel level = server.overworld();
        switch (words[0]) {
            case "stats" -> {
                return new Gson().toJson(FabricEdits.get(server).publicationStatistics());
            }
            case "fault-skip-reconcile" -> {
                BridgeFaults.skipReconcile = Boolean.parseBoolean(words[1]);
                return new Gson().toJson(Map.of("skipReconcile", BridgeFaults.skipReconcile));
            }
            case "fixture" -> {
                require(current == null || current.future == null || current.future.isDone(), "Previous operation active");
                current = new Run(nextChunk, words[1]);
                nextChunk += 3;
                Run run = current;
                ChunkPos pos = new ChunkPos(run.cx, Z);
                fixture = level.getChunkSource().addTicketAndLoadWithRadius(TicketType.FORCED, pos, 0)
                        .thenRunAsync(() -> {
                            LevelChunk chunk = level.getChunkSource().getChunkNow(run.cx, Z);
                            require(chunk != null, "Fixture is loaded");
                            for (int i = 0; i < 4096; i++) {
                                BlockPos cell = position(run.cx, i);
                                chunk.setBlockState(cell, (run.kind.equals("remove") ? Blocks.GLOWSTONE : Blocks.STONE)
                                        .defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                            }
                            if (run.kind.equals("be-remove")) {
                                for (int i = 0; i < 2; i++) chunk.setBlockState(position(run.cx, i),
                                        Blocks.FURNACE.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                            }
                            level.scheduleTick(position(run.cx, 2048), Blocks.STONE, 1_000_000);
                            if (run.kind.startsWith("nbt") || run.kind.equals("mid-failure")) {
                                for (int i = 0; i < 2; i++) {
                                    chunk.setBlockState(position(run.cx, i), Blocks.CHEST.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                                }
                            }
                        }, server);
                tickets.add(() -> level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, pos, 0));
            }
            case "start" -> {
                require(fixture.isDone() && !fixture.isCompletedExceptionally(), "Fixture setup incomplete");
                ServerPlayer player = server.getPlayerList().getPlayerByName(words[1]);
                require(player != null, "Actor is connected");
                Run run = current;
                run.context = new EditActorContext(player.getUUID(), words[1], EditActorContext.Source.API,
                        UUID.randomUUID(), null, null, "minecraft:overworld",
                        run.kind.equals("poi") ? SideEffectSet.none().with(com.sk89q.worldedit.util.SideEffect.POI_UPDATE)
                                : SideEffectSet.none(), new EditActorContext.Cancellation());
                RUNS.put(run.context.operationId(), run);
                var service = FabricEdits.get(server);
                lastService = service;
                run.before = service.publicationStatistics();
                java.util.function.Consumer<com.fastasyncworldedit.core.queue.implementation.SingleThreadQueueExtent> edit = queue -> {
                    if (run.kind.equals("entity")) {
                        var tag = new net.minecraft.nbt.CompoundTag();
                        tag.putString("id", "minecraft:armor_stand");
                        queue.getOrCreateChunk(run.cx, Z).entity(com.fastasyncworldedit.core.nbt.FaweCompoundTag.of(
                                com.sk89q.worldedit.fabric.internal.NBTConverter.fromNative(tag)));
                        return;
                    }
                    if (run.kind.equals("biome")) {
                        for (int x = 0; x < 16; x += 4) for (int z = 0; z < 16; z += 4) for (int y = 80; y < 96; y += 4) {
                            queue.setBiome((run.cx << 4) + x, y, (Z << 4) + z, com.sk89q.worldedit.world.biome.BiomeTypes.DESERT);
                        }
                        return;
                    }
                    for (int i = 0; i < 4096; i++) {
                        BlockPos pos = position(run.cx, i);
                        if (run.kind.startsWith("nbt") || run.kind.equals("mid-failure")) {
                            if (i > 1) break;
                            var nbt = com.sk89q.worldedit.fabric.internal.NBTConverter.toNative(queue.getFullBlock(pos.getX(), pos.getY(), pos.getZ()).getNbt());
                            nbt.putString("CustomName", "s5-after");
                            queue.tile(pos.getX(), pos.getY(), pos.getZ(), com.fastasyncworldedit.core.nbt.FaweCompoundTag.of(
                                    com.sk89q.worldedit.fabric.internal.NBTConverter.fromNative(nbt)));
                        } else if (!run.kind.equals("sparse") || i % 7 == 0) {
                            if (Set.of("furnace", "poi", "unknown").contains(run.kind) && i > 1) break;
                            if (run.kind.equals("heavy-limit") && i > 64) break;
                            var target = switch (run.kind) {
                                case "fluid" -> BlockTypes.WATER;
                                case "random" -> BlockTypes.GRASS_BLOCK;
                                case "light" -> BlockTypes.GLOWSTONE;
                                case "remove" -> BlockTypes.AIR;
                                case "furnace", "heavy-limit" -> BlockTypes.FURNACE;
                                case "poi" -> BlockTypes.BELL;
                                case "unknown" -> BlockTypes.SCULK_SENSOR;
                                default -> BlockTypes.GOLD_BLOCK;
                            };
                            if (!queue.getBlock(pos.getX(), pos.getY(), pos.getZ()).getBlockType().equals(BlockTypes.DIAMOND_BLOCK)) {
                                queue.setBlock(pos.getX(), pos.getY(), pos.getZ(), target.getDefaultState());
                            }
                        }
                    }
                };
                run.future = (run.kind.equals("retry") || run.kind.equals("nbt-retry") || run.kind.equals("nbt-aba")
                        ? service.submitRetrying(run.context, BlockVector2.at(run.cx, Z), run.history, edit)
                        : service.submitIndependent(run.context, List.of(BlockVector2.at(run.cx, Z)), run.history, edit)).toCompletableFuture();
                run.future.whenComplete((value, failure) -> {
                    if (value != null) run.receipts = value;
                    Throwable cause = failure;
                    while (cause != null && !(cause instanceof FabricEdits.OperationFailure) && cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    if (cause instanceof FabricEdits.OperationFailure problem) run.receipts = problem.publishedReceipts();
                    run.failure = failure == null ? "" : failure.toString();
                });
                cases.add(run);
            }
            case "mutate" -> {
                BlockPos cell = position(current.cx, 4095);
                level.setBlock(cell, Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                if (current.kind.equals("aba")) level.setBlock(cell, Blocks.STONE.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
            }
            case "release" -> current.gate.complete(null);
            case "release-send" -> current.sendGate.complete(null);
            case "cancel" -> current.context.cancellation().cancel();
            case "verify" -> { return new Gson().toJson(verify(level, current)); }
            case "verify-saved" -> {
                Run saved = new Run(Integer.parseInt(words[1]), words[2]);
                verifyWorld(level, saved);
                return "{\"savedWorld\":true}";
            }
            case "verify-all" -> {
                List<Map<String, Object>> observations = new ArrayList<>();
                for (Run run : cases) observations.add(verify(level, run));
                return new Gson().toJson(observations);
            }
            case "wna-biome" -> {
                var chunk = level.getChunkSource().getChunkNow(69, 65);
                require(chunk != null, "WNA biome oracle requires loaded state");
                require(chunk.getNoiseBiome(276, 28, 260).unwrapKey().orElseThrow().identifier().toString().equals("minecraft:desert"), "WNA target quart cell");
                require(chunk.getNoiseBiome(277, 28, 260).unwrapKey().orElseThrow().identifier().toString().equals("minecraft:plains"), "WNA x neighbor quart cell");
                require(chunk.getNoiseBiome(276, 28, 261).unwrapKey().orElseThrow().identifier().toString().equals("minecraft:plains"), "WNA z neighbor quart cell");
                return "{\"quartCells\":3}";
            }
            case "hold-commands" -> {
                FabricEdits.get(server).preparationWorkers(2);
                commandGate = new CompletableFuture<>();
            }
            case "release-commands" -> commandGate.complete(null);
            case "overlap-world" -> {
                for (int x = 1104; x <= 1135; x++) for (int y = 96; y <= 111; y++) for (int z = 1040; z <= 1055; z++) {
                    var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                    require(chunk != null && chunk.getBlockState(new BlockPos(x, y, z)).is(Blocks.DIAMOND_BLOCK), "Serial overlap outcome");
                }
                return "{\"cells\":8192}";
            }
            case "command-world" -> {
                boolean replaced = words[1].equals("replace");
                for (int x = 1040; x <= 1071; x++) for (int y = 96; y <= 111; y++) for (int z = 1040; z <= 1055; z++) {
                    var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                    require(chunk != null, "Command oracle refuses unloaded chunks");
                    var expected = replaced ? x == 1040 && z == 1040 ? Blocks.DIAMOND_BLOCK : Blocks.GLASS : Blocks.GOLD_BLOCK;
                    require(chunk.getBlockState(new BlockPos(x, y, z)).is(expected), "Independent command cell " + x + "," + y + "," + z);
                }
                return "{\"cells\":8192}";
            }
            case "commands" -> {
                return new Gson().toJson(Map.of("complete", com.fastasyncworldedit.fabric.edit.FabricRegionCommands.completed(),
                        "prepared", RUNS.values().stream().filter(run -> run.kind.equals("command")).mapToInt(run -> run.prepared.get()).sum(),
                        "recorded", RUNS.values().stream().filter(run -> run.kind.equals("command")).mapToInt(run -> run.recorded.get()).sum(),
                        "publication", FabricEdits.get(server).publicationStatistics(),
                        "preparation", FabricEdits.get(server).preparationStatistics()));
            }
            case "cleanup" -> {
                commandGate.complete(null);
                cases.forEach(run -> {
                    run.context.cancellation().cancel();
                    run.gate.complete(null);
                    run.sendGate.complete(null);
                });
                tickets.forEach(Runnable::run);
                tickets.clear();
            }
            case "status" -> { }
            default -> throw new IllegalArgumentException(args);
        }
        return new Gson().toJson(Map.of("fixture", fixture.isDone() && !fixture.isCompletedExceptionally(),
                "cx", current == null ? 0 : current.cx, "prepared", current == null ? 0 : current.prepared.get(),
                "published", current == null ? 0 : current.published.get(),
                "tickets", FabricEdits.get(server).preparationStatistics().get("tickets"),
                "complete", current != null && current.future != null && current.future.isDone()));
    }

    private static void verifyWorld(ServerLevel level, Run run) throws Exception {
        LevelChunk chunk = level.getChunkSource().getChunkNow(run.cx, Z);
        require(chunk != null, "Oracle refuses unloaded chunks");
        boolean conflict = Set.of("race", "aba").contains(run.kind);
        boolean cancelled = run.kind.equals("cancel");
        int expectedChanges = expectedChanges(run.kind);
        boolean nbt = run.kind.startsWith("nbt") || run.kind.equals("mid-failure");
        for (int i = 0; i < 4096; i++) {
            BlockState expected = nbt && i < 2 ? Blocks.CHEST.defaultBlockState()
                    : !nbt && !conflict && !cancelled && (!run.kind.equals("sparse") || i % 7 == 0)
                            ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.STONE.defaultBlockState();
            expected = switch (run.kind) {
                case "fluid" -> Blocks.WATER.defaultBlockState();
                case "random" -> Blocks.GRASS_BLOCK.defaultBlockState();
                case "light" -> Blocks.GLOWSTONE.defaultBlockState();
                case "remove" -> Blocks.AIR.defaultBlockState();
                case "furnace" -> (i < 2 ? Blocks.FURNACE : Blocks.STONE).defaultBlockState();
                case "poi" -> (i < 2 ? Blocks.BELL : Blocks.STONE).defaultBlockState();
                case "unknown", "entity", "biome", "heavy-limit" -> Blocks.STONE.defaultBlockState();
                default -> expected;
            };
            if (i == 4095 && (run.kind.equals("race") || run.kind.equals("retry"))) expected = Blocks.DIAMOND_BLOCK.defaultBlockState();
            require(chunk.getBlockState(position(run.cx, i)) == expected, "Independent cell mismatch at " + i);
        }
        if (nbt) {
            for (int i = 0; i < 2; i++) {
                var entity = chunk.getBlockEntity(position(run.cx, i));
                require(entity != null && !entity.isRemoved(), "Live block entity retained");
                boolean changed = entity.saveWithFullMetadata(level.registryAccess()).toString().contains("s5-after");
                require(changed == (i < expectedChanges), "NBT-only actual subset");
                if (run.kind.equals("nbt-retry") || run.kind.equals("nbt-aba")) {
                    var stack = ((net.minecraft.world.Container) entity).getItem(0);
                    if (run.kind.equals("nbt-retry") && i == 0) {
                        require(stack.is(net.minecraft.world.item.Items.DIAMOND) && stack.getCount() == 3, "Concurrent inventory edit preserved");
                    } else require(stack.isEmpty(), "Inventory absence/ABA preserved");
                }
            }
        }
        var section = chunk.getSection(chunk.getSectionIndex(80));
        for (String field : List.of("nonEmptyBlockCount", "fluidCount", "tickingBlockCount", "tickingFluidCount")) {
            var member = section.getClass().getDeclaredField(field);
            member.setAccessible(true);
            int expectedCount = switch (field) {
                case "nonEmptyBlockCount" -> run.kind.equals("remove") ? 0 : 4096;
                case "fluidCount" -> run.kind.equals("fluid") ? 4096 : 0;
                case "tickingBlockCount" -> run.kind.equals("random") ? 4096 : 0;
                default -> 0;
            };
            require(member.getShort(section) == expectedCount, "Incorrect section counter " + field);
        }
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int height = run.kind.equals("remove") ? -64 : 95;
            require(chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) == height, "Independent surface height");
            require(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) == height, "Independent motion height");
            int sky = height + 1;
            require(chunk.getSkyLightSources().getLowestSourceY(x, z) == sky,
                    "Independent sky source: expected=" + sky + ", actual=" + chunk.getSkyLightSources().getLowestSourceY(x, z));
        }
        if (run.kind.equals("entity")) {
            for (var entity : level.getAllEntities()) {
                require(!(entity instanceof net.minecraft.world.entity.decoration.ArmorStand)
                        || entity.chunkPosition().x() != run.cx || entity.chunkPosition().z() != Z,
                        "Refused entity was created outside receipts");
            }
        }
        require(chunk.getBlockTicks().hasScheduledTick(position(run.cx, 2048), Blocks.STONE), "Existing scheduled tick retained");
        if (run.kind.equals("biome")) {
            for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) for (int y = 20; y < 24; y++) {
                require(chunk.getNoiseBiome(run.cx * 4 + x, y, Z * 4 + z).unwrapKey().orElseThrow().identifier()
                        .toString().equals("minecraft:desert"), "Independent biome cell");
            }
            require(chunk.getNoiseBiome(run.cx * 4, 24, Z * 4).unwrapKey().orElseThrow().identifier()
                    .toString().equals("minecraft:plains"), "Unchanged biome section preserved");
        }
        if (run.kind.equals("light") || run.kind.equals("remove")) {
            require(level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, position(run.cx, 2184))
                    == (run.kind.equals("light") ? 15 : 0), "Block light converged before completion");
        }
        if (run.kind.equals("furnace") || run.kind.equals("be-remove")) {
            var member = LevelChunk.class.getDeclaredField("tickersInLevel");
            member.setAccessible(true);
            Map<?, ?> tickers = (Map<?, ?>) member.get(chunk);
            for (int i = 0; i < 2; i++) {
                boolean exists = run.kind.equals("furnace");
                require(tickers.containsKey(position(run.cx, i)) == exists, "Block entity ticker lifecycle");
                require((chunk.getBlockEntity(position(run.cx, i)) != null) == exists, "Block entity lifecycle");
            }
        }
        if (run.kind.equals("poi")) {
            for (int i = 0; i < 2; i++) require(level.getPoiManager().getType(position(run.cx, i)).isPresent(), "Bell POI registered");
        }
    }

    private static int expectedChanges(String kind) {
        return switch (kind) {
            case "sparse" -> 586;
            case "nbt", "nbt-retry", "nbt-aba", "furnace", "poi" -> 2;
            case "mid-failure" -> 1;
            case "retry" -> 4095;
            case "biome" -> 64;
            case "race", "aba", "cancel", "unknown", "entity", "heavy-limit" -> 0;
            default -> 4096;
        };
    }

    private static Map<String, Object> verify(ServerLevel level, Run run) throws Exception {
        require(run.future.isDone(), "Completion includes maintenance and history");
        verifyWorld(level, run);
        boolean conflict = Set.of("race", "aba").contains(run.kind);
        boolean cancelled = run.kind.equals("cancel");
        boolean nbt = run.kind.startsWith("nbt") || run.kind.equals("mid-failure");
        int expectedChanges = expectedChanges(run.kind);
        int applied = run.receipts.stream().mapToInt(receipt -> receipt.applied().size()).sum();
        require(applied == expectedChanges && run.history.size() == expectedChanges,
                "Actual receipts/history count: expected=" + expectedChanges + ", receipt=" + applied + ", history=" + run.history.size());
        require(run.closed.get() == run.prepared.get(), "Reservation released exactly once per attempt");
        if (run.after == null) run.after = FabricEdits.get(level.getServer()).publicationStatistics();
        var stats = run.after;
        // With holder owners installed, a proposal mixing block-entity cells cannot go direct and
        // must not bulk-install without a token, so every cell takes the ordinary route.
        boolean perCell = run.kind.equals("be-remove") && !EditIntegrations.bulkInstallWithoutTokenSupported();
        if (perCell) {
            require(stats.get("sections").equals(run.before.get("sections"))
                    && stats.get("fallbackWrites") - run.before.get("fallbackWrites") == expectedChanges,
                    "Mixed lifecycle proposal must use only per-cell writes while holder owners are installed");
        } else if (!nbt && expectedChanges > 0 && !Set.of("furnace", "poi", "biome").contains(run.kind)) {
            require(stats.get("sections") > run.before.get("sections"), "Real section installation did not run");
        }
        if (Set.of("unknown", "entity", "heavy-limit").contains(run.kind)) require(run.future.isCompletedExceptionally(), "Unsupported capability must refuse");
        require(FabricEdits.get(level.getServer()).preparationStatistics().get("tickets") == 0, "Operation tickets released");
        return Map.of("kind", run.kind, "cx", run.cx, "applied", applied, "history", run.history.size(),
                "attempts", run.prepared.get(), "failure", run.failure, "publication", stats);
    }

    public static void afterFallbackWrite() {
        Run run = current;
        if (run != null && run.kind.equals("mid-failure") && run.future != null && !run.future.isDone()) {
            throw new IllegalStateException("S5 injected failure after one native write");
        }
    }

    private static BlockPos position(int cx, int index) {
        return new BlockPos((cx << 4) + (index & 15), 80 + (index >> 8), (Z << 4) + ((index >> 4) & 15));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Run {
        private final int cx;
        private final String kind;
        private final CompletableFuture<Void> gate = new CompletableFuture<>();
        private final CompletableFuture<Void> sendGate = new CompletableFuture<>();
        private final AtomicInteger published = new AtomicInteger();
        private final AtomicInteger prepared = new AtomicInteger();
        private final AtomicInteger recorded = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();
        private final ArrayListHistory history = new ArrayListHistory();
        private EditActorContext context;
        private CompletableFuture<List<CommitReceipt>> future;
        private List<CommitReceipt> receipts = List.of();
        private Map<String, Long> before;
        // Statistics at this run's first verification; verify-all reruns later, after other runs.
        private Map<String, Long> after;
        private String failure = "";
        private Run(int cx, String kind) { this.cx = cx; this.kind = kind; }
    }
}
