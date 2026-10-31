package com.fastasyncworldedit.testharness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.fastasyncworldedit.core.extent.filter.block.FilterBlock;
import com.fastasyncworldedit.core.queue.Filter;
import com.fastasyncworldedit.core.queue.implementation.chunk.ChunkHolder;
import com.fastasyncworldedit.fabric.compat.EditIntegration;
import com.fastasyncworldedit.fabric.edit.CommitReceipt;
import com.fastasyncworldedit.fabric.edit.EditActorContext;
import com.fastasyncworldedit.fabric.edit.EditDelta;
import com.fastasyncworldedit.fabric.edit.FabricEdits;
import com.fastasyncworldedit.fabric.edit.FabricGetBlocks;
import com.fastasyncworldedit.fabric.edit.FabricPreparation;
import com.fastasyncworldedit.fabric.edit.PreparedChunkEdit;
import com.fastasyncworldedit.fixture.PaletteFixture;
import com.google.gson.Gson;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.sk89q.worldedit.history.changeset.ArrayListHistory;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/** Real service consumer with independent native-world oracles and external fault barriers. */
public final class PreparationProbe implements ModInitializer {
    private static final IntegerProperty VARIANT = PaletteFixture.VARIANT;
    private static final Map<UUID, Run> runs = new ConcurrentHashMap<>();
    private static final List<Run> current = new ArrayList<>();
    private static final List<Runnable> fixtureTickets = new ArrayList<>();
    private static final List<String> startedOrder = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static CompletableFuture<Void> fixture = CompletableFuture.completedFuture(null);
    private static CompletableFuture<Void> release = CompletableFuture.completedFuture(null);
    private static String scenario = "idle";
    private static int seedIndex;
    private static int baseX;
    private static final int BASE_Z = -240;
    private static String fixtureDimension = "minecraft:overworld";
    private static final AtomicInteger workersEntered = new AtomicInteger();
    private static final AtomicInteger violations = new AtomicInteger();
    private static long startSnapshots;
    private static boolean cancelled;
    private static AttachmentType<String> attachment;
    private static final List<Integer> paletteBits = new ArrayList<>();

    @Override
    public void onInitialize() {
        attachment = AttachmentRegistry.create(Identifier.parse("fawe_test:unknown"));
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
                Commands.literal("faweprep").then(Commands.argument("arguments", StringArgumentType.greedyString())
                        .executes(command -> {
                            try {
                                String result = control(command.getSource().getServer(),
                                        StringArgumentType.getString(command, "arguments"));
                                command.getSource().sendSuccess(() -> Component.literal(result), false);
                                return 1;
                            } catch (Throwable failure) {
                                command.getSource().sendFailure(Component.literal(failure.toString()));
                                return 0;
                            }
                        }))));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (current.isEmpty() || cancelled || !scenario.startsWith("cancel-")) return;
            Map<String, Long> stats = FabricEdits.get(server).preparationStatistics();
            boolean cancel = switch (scenario) {
                case "cancel-load" -> stats.get("tickets") > 0 && stats.get("snapshots") == startSnapshots;
                case "cancel-snapshot" -> stats.get("snapshots") == startSnapshots + 1;
                case "cancel-preparation" -> workersEntered.get() > 0;
                default -> false;
            };
            if (cancel) {
                cancelled = true;
                current.forEach(run -> run.context.cancellation().cancel());
                release.complete(null);
            }
        });
    }

    /** Fail the native run if detached workers call live world reads. */
    public static void checkLiveRead() {
        require(!Thread.currentThread().getName().equals("fawe-coordinated-preparation"),
                "Preparation worker accessed live world");
    }

    public static boolean owns(PreparedChunkEdit proposal) {
        return runs.containsKey(proposal.context().operationId());
    }

    public static CompletionStage<EditIntegration.Reservation> prepare(PreparedChunkEdit proposal) {
        Run run = runs.get(proposal.context().operationId());
        run.proposals.add(proposal);
        return CompletableFuture.completedFuture(new EditIntegration.Reservation() {
            @Override
            public boolean authorize(ServerLevel level, ServerPlayer player, EditActorContext context, EditDelta delta) {
                require(level.getServer().isSameThread(), "Worker touched live authorization");
                return true;
            }
            @Override
            public void beforePublication(ServerLevel level, List<EditDelta> changes) { }
            @Override
            public CompletionStage<Void> reconcile(ServerLevel level, CommitReceipt receipt) {
                return CompletableFuture.completedFuture(null);
            }
            @Override
            public CompletionStage<Void> record(CommitReceipt receipt) { return CompletableFuture.completedFuture(null); }
            @Override
            public void close(ServerLevel level) { }
            @Override
            public void discard() { }
        });
    }

    private static String control(MinecraftServer server, String arguments) {
        String[] words = arguments.split(" ");
        FabricEdits service = FabricEdits.get(server);
        switch (words[0]) {
            case "registry" -> { return new Gson().toJson(PaletteFixture.describe()); }
            case "fixture" -> setup(server, words[1]);
            case "start" -> start(server, words[1], words[2], Integer.parseInt(words[3]),
                    words.length > 4 ? words[4] : words[1]);
            case "observe" -> {
                require(current.stream().allMatch(run -> run.future.isDone()), "Observe only after service drain");
                ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(fixtureDimension)));
                List<CompletableFuture<?>> loads = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    ChunkPos pos = new ChunkPos(baseX + i - 128, BASE_Z);
                    loads.add(level.getChunkSource().addTicketAndLoadWithRadius(TicketType.FORCED, pos, 0));
                    fixtureTickets.add(() -> level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, pos, 0));
                }
                fixture = CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new));
            }
            case "mutate" -> {
                require(workersEntered.get() > 0, "Mutation requires the detached snapshot barrier");
                ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(fixtureDimension)));
                var neighbor = level.getChunkSource().getChunkNow(baseX + 1, BASE_Z);
                if (scenario.equals("attachment-publication")) {
                    ((AttachmentTarget) neighbor).setAttached(attachment, "opaque-test-payload");
                } else {
                    BlockPos pos = new BlockPos((baseX + 1) * 16, 80, BASE_Z * 16);
                    neighbor.setBlockState(pos, Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                    if (scenario.equals("neighbor-aba")) {
                        neighbor.setBlockState(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                    }
                }
            }
            case "release" -> release.complete(null);
            case "cleanup" -> {
                fixtureTickets.forEach(Runnable::run);
                fixtureTickets.clear();
                current.clear();
                runs.clear();
            }
            case "verify" -> { return new Gson().toJson(verify(server)); }
            case "status" -> { }
            default -> throw new IllegalArgumentException(arguments);
        }
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("scenario", scenario);
        state.put("fixture", fixture.isDone() && !fixture.isCompletedExceptionally());
        state.put("fixtureFailure", fixture.isCompletedExceptionally());
        state.put("complete", !current.isEmpty() && current.stream().allMatch(run -> run.future != null && run.future.isDone()));
        state.put("workersEntered", workersEntered.get());
        state.put("cancelled", cancelled);
        state.put("violations", violations.get());
        state.put("order", List.copyOf(startedOrder));
        state.put("stats", service.preparationStatistics());
        state.put("x", baseX << 4);
        state.put("z", BASE_Z << 4);
        state.put("dimension", fixtureDimension);
        return new Gson().toJson(state);
    }

    private static void setup(MinecraftServer server, String kind) {
        require(current.stream().allMatch(run -> run.future.isDone()), "Previous run not drained");
        current.clear();
        runs.clear();
        fixtureTickets.forEach(Runnable::run);
        fixtureTickets.clear();
        baseX = -240 - seedIndex++ * 8;
        fixtureDimension = kind.equals("height") ? "fawe_test:tall" : "minecraft:overworld";
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(fixtureDimension)));
        require(level != null, "Missing custom-height fixture dimension");
        List<CompletableFuture<?>> loads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int cx = baseX + i;
            loads.add(level.getChunkSource().addTicketAndLoadWithRadius(TicketType.FORCED, new ChunkPos(cx, BASE_Z), 0));
            fixtureTickets.add(() -> level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, new ChunkPos(cx, BASE_Z), 0));
        }
        fixture = CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).thenRunAsync(() -> {
            paletteBits.clear();
            for (int chunk = 0; chunk < 4; chunk++) {
                var live = level.getChunkSource().getChunkNow(baseX + chunk, BASE_Z);
                require(live != null, "Fixture must be loaded");
                for (int index = 0; index < 4096; index++) {
                    int x = index & 15, y = 80 + (index >> 8), z = (index >> 4) & 15;
                    var state = index % 31 == 0 ? Blocks.STONE.defaultBlockState()
                            : kind.equals("local") ? PaletteFixture.block.defaultBlockState().setValue(VARIANT, index % 4)
                            : kind.equals("mixed") && index % 3 == 0 ? Blocks.OAK_LOG.defaultBlockState()
                            : PaletteFixture.block.defaultBlockState().setValue(VARIANT, index % 1024);
                    live.setBlockState(new BlockPos((baseX + chunk) * 16 + x, y, BASE_Z * 16 + z), state,
                            Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                }
                int bits = live.getSection(live.getSectionIndex(80)).getStates().bitsPerEntry();
                paletteBits.add(bits);
                require(kind.equals("local") ? bits <= 8 : bits > 8, "Fixture did not select native local/global palette");
                if (kind.equals("attachment-snapshot")) {
                    ((AttachmentTarget) live).setAttached(attachment, "opaque-test-payload");
                }
                for (int y = level.getMinY(); y <= level.getMaxY(); y++) {
                    if ((y & 15) == 0 || (y & 15) == 15) {
                        live.setBlockState(new BlockPos((baseX + chunk) * 16, y, BASE_Z * 16),
                                Blocks.STONE.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                    }
                }
            }
        }, server);
        scenario = kind;
    }

    private static void start(MinecraftServer server, String name, String selected, int workerCount, String second) {
        require(fixture.isDone() && !fixture.isCompletedExceptionally(), "Fixture is not ready");
        require(current.stream().allMatch(run -> run.future.isDone()), "Previous run not drained");
        current.clear();
        startedOrder.clear();
        workersEntered.set(0);
        violations.set(0);
        cancelled = false;
        scenario = selected;
        FabricEdits service = FabricEdits.get(server);
        service.preparationWorkers(workerCount);
        startSnapshots = service.preparationStatistics().get("snapshots");
        boolean neighbor = selected.startsWith("neighbor-") || selected.equals("attachment-publication");
        boolean hold = neighbor || selected.equals("reload") || selected.equals("saturation") || selected.equals("cancel-preparation");
        release = hold ? new CompletableFuture<>() : CompletableFuture.completedFuture(null);
        int count = selected.equals("saturation") ? 8 : 1;
        for (int operation = 0; operation < count; operation++) {
            ServerPlayer player = server.getPlayerList().getPlayerByName(operation == 7 ? second : name);
            require(player != null, "Missing connected actor");
            EditActorContext context = new EditActorContext(player.getUUID(), player.getName().getString(),
                    EditActorContext.Source.API, UUID.randomUUID(), null, null, fixtureDimension,
                    SideEffectSet.none(), new EditActorContext.Cancellation());
            Run run = new Run(context);
            runs.put(context.operationId(), run);
            current.add(run);
            List<FabricEdits.Unit> units = new ArrayList<>();
            int unitsCount = selected.equals("saturation") ? operation == 0 ? 57 : 1 : neighbor ? 1 : 4;
            for (int i = 0; i < unitsCount; i++) {
                int cx = baseX + i % 4;
                if (selected.startsWith("cold") || selected.startsWith("cancel-")) cx -= 128;
                final int chunkX = cx;
                String label = operation + "/" + i;
                List<BlockVector2> footprint = selected.equals("cancel-snapshot")
                        ? List.of(BlockVector2.at(cx, BASE_Z), BlockVector2.at(cx + 1, BASE_Z),
                                BlockVector2.at(cx + 2, BASE_Z), BlockVector2.at(cx + 3, BASE_Z))
                        : neighbor ? List.of(BlockVector2.at(cx, BASE_Z), BlockVector2.at(cx + 1, BASE_Z))
                        : List.of(BlockVector2.at(cx, BASE_Z));
                units.add(FabricEdits.Unit.independent(footprint, queue -> {
                    require(!com.fastasyncworldedit.core.Fawe.isMainThread(), "Filter executed on owner thread");
                    workersEntered.incrementAndGet();
                    startedOrder.add(label);
                    try { release.get(30, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                    if (selected.equals("saturation")) return;
                    var holder = (ChunkHolder<?>) queue.getOrCreateChunk(chunkX, BASE_Z);
                    FabricGetBlocks snapshot = (FabricGetBlocks) holder.getOrCreateGet();
                    var copy = snapshot.getCopy(snapshot.setCreateCopy(true));
                    char[] isolated = copy.load(5);
                    char original = isolated[0];
                    isolated[0] = 0;
                    require(snapshot.load(5)[0] == original, "Snapshot copy exposed backing ordinals");
                    if (selected.equals("oversized-nbt-text") || selected.equals("oversized-nbt-array")) {
                        var payload = selected.equals("oversized-nbt-text")
                                ? org.enginehub.linbus.tree.LinStringTag.of("x".repeat(40000))
                                : org.enginehub.linbus.tree.LinIntArrayTag.of(new int[20000]);
                        queue.tile(chunkX * 16, 80, BASE_Z * 16,
                                com.fastasyncworldedit.core.nbt.FaweCompoundTag.of(
                                        org.enginehub.linbus.tree.LinCompoundTag.of(Map.of("payload", payload))));
                    } else if (selected.equals("oversized-reads")) {
                        snapshot.getHeightMap(com.fastasyncworldedit.core.extent.processor.heightmap.HeightMapType.WORLD_SURFACE);
                        queue.setBlock(chunkX * 16, 80, BASE_Z * 16, BlockTypes.GOLD_BLOCK.getDefaultState());
                    } else if (selected.equals("oversized-writes")) {
                        for (int y = 80; y < 112; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            queue.setBlock(chunkX * 16 + x, y, BASE_Z * 16 + z, BlockTypes.GOLD_BLOCK.getDefaultState());
                        }
                    } else if (neighbor) {
                        require(queue.getBlock((chunkX + 1) * 16, 80, BASE_Z * 16).getBlockType() == BlockTypes.STONE,
                                "Neighbor snapshot was not detached");
                        queue.setBlock(chunkX * 16, 80, BASE_Z * 16, BlockTypes.GOLD_BLOCK.getDefaultState());
                    } else if (selected.equals("height")) {
                        for (int y = snapshot.getMinY(); y <= snapshot.getMaxY(); y++) {
                            if ((y & 15) == 0 || (y & 15) == 15) queue.setBlock(chunkX * 16, y, BASE_Z * 16,
                                    BlockTypes.GOLD_BLOCK.getDefaultState());
                        }
                    } else if (selected.equals("entities")) {
                        snapshot.entities();
                    } else if (selected.startsWith("cold") || selected.startsWith("cancel-") || selected.equals("saturation")) {
                        queue.setBlock(chunkX * 16 + 1, 80, BASE_Z * 16 + 1, BlockTypes.GOLD_BLOCK.getDefaultState());
                    } else {
                        CuboidRegion region = new CuboidRegion(BlockVector3.at(chunkX * 16, 80, BASE_Z * 16),
                                BlockVector3.at(chunkX * 16 + 15, 95, BASE_Z * 16 + 15));
                        FabricPreparation.apply(queue, chunkX, BASE_Z, region, new Filter() {
                            @Override
                            public void applyBlock(FilterBlock block) {
                                if ((block.x() & 15) == 0 && (block.z() & 15) == 0) {
                                    var cursor = (com.fastasyncworldedit.core.extent.filter.block.CharFilterBlock) block;
                                    if ((block.y() & 15) == 0) {
                                        require(cursor.getBlockBelow().getBlockType() == BlockTypes.STONE
                                                && cursor.getBlockRelativeY(-1).getBlockType() == BlockTypes.STONE,
                                                "Lower-section filter read mismatch");
                                    } else if ((block.y() & 15) == 15) {
                                        require(cursor.getBlockAbove().getBlockType() == BlockTypes.STONE
                                                && cursor.getBlockRelativeY(1).getBlockType() == BlockTypes.STONE,
                                                "Upper-section filter read mismatch");
                                    }
                                }
                                String actual = block.getBlock().getAsString();
                                if (actual.equals("minecraft:stone")) block.setBlock(BlockTypes.GOLD_BLOCK.getDefaultState());
                                else require(actual.startsWith("fawe_test:palette[variant=") || actual.startsWith("minecraft:oak_log["),
                                        "Incorrect mixed-palette read: " + actual);
                            }
                        });
                    }
                }));
            }
            run.future = service.submitUnits(context, units, new ArrayListHistory()).toCompletableFuture();
            if (selected.equals("cancel-queued")) {
                context.cancellation().cancel();
                cancelled = true;
            }
        }
        if (selected.equals("saturation")) {
            try {
                service.submitIndependent(current.getFirst().context, List.of(BlockVector2.at(baseX, BASE_Z)),
                        new ArrayListHistory(), queue -> { });
                throw new AssertionError("Ninth operation escaped admission budget");
            } catch (IllegalArgumentException expected) { }
        }
    }

    private static Map<String, Object> verify(MinecraftServer server) {
        require(!current.isEmpty() && current.stream().allMatch(run -> run.future.isDone()), "Run incomplete");
        var stats = FabricEdits.get(server).preparationStatistics();
        require(stats.get("bytes") == 0 && stats.get("tickets") == 0 && stats.get("active") == 0 && stats.get("queued") == 0,
                "Service leaked reservation, snapshot or ticket");
        require(stats.get("acquired").equals(stats.get("released")), "Ticket acquisition/release mismatch");
        require(stats.get("peakBytes") <= (32L << 20), "Snapshot byte cap exceeded");
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(fixtureDimension)));
        boolean refusal = scenario.startsWith("cancel-") || scenario.equals("reload") || scenario.equals("entities")
                || scenario.startsWith("neighbor-") || scenario.startsWith("attachment-") || scenario.startsWith("oversized-");
        int applied = 0;
        int proposals = 0;
        for (Run run : current) {
            if (scenario.startsWith("oversized-nbt-")) {
                require(failure(run.future).contains("NBT conversion budget exceeded"), "NBT was not refused before conversion");
            }
            if (!refusal) require(!run.future.isCompletedExceptionally(), "Operation failed: " + failure(run.future));
            List<CommitReceipt> receipts;
            try { receipts = run.future.join(); }
            catch (Exception failure) {
                Throwable cause = failure;
                while (!(cause instanceof FabricEdits.OperationFailure) && cause.getCause() != null) cause = cause.getCause();
                require(cause instanceof FabricEdits.OperationFailure, "Missing failure receipts");
                receipts = ((FabricEdits.OperationFailure) cause).publishedReceipts();
            }
            applied += receipts.stream().mapToInt(receipt -> receipt.applied().size()).sum();
            proposals += run.proposals.stream().mapToInt(proposal -> proposal.writes().size()).sum();
            for (var proposal : run.proposals) {
                for (var delta : proposal.writes()) {
                    require(delta.after().id().equals("minecraft:gold_block"), "Unexpected proposal output");
                    require(delta.before().id().equals(scenario.startsWith("cold") || scenario.startsWith("cancel-")
                            ? "minecraft:air" : "minecraft:stone"), "Unexpected proposal before-state");
                }
            }
        }
        int observed = 0;
        if (refusal) {
            require(applied == 0, "Cancelled/reload/unsupported operation wrote blocks");
            for (int chunk = 0; chunk < 4; chunk++) {
                int cx = baseX + chunk - (scenario.startsWith("cancel-") ? 128 : 0);
                var live = level.getChunkSource().getChunkNow(cx, BASE_Z);
                require(live != null, "Refusal oracle requires loaded state");
                for (int y = 80; y < 112; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    require(!live.getBlockState(new BlockPos(cx * 16 + x, y, BASE_Z * 16 + z)).is(Blocks.GOLD_BLOCK),
                            "Refused operation left an unreported write");
                }
            }
        }
        if (!refusal && !scenario.equals("saturation")) {
            for (int chunk = 0; chunk < 4; chunk++) {
                int cx = baseX + chunk - (scenario.startsWith("cold") ? 128 : 0);
                var live = level.getChunkSource().getChunkNow(cx, BASE_Z);
                require(live != null, "Independent world oracle cannot use unloaded state");
                if (scenario.equals("height")) {
                    for (int y = level.getMinY(); y <= level.getMaxY(); y++) {
                        if ((y & 15) != 0 && (y & 15) != 15) continue;
                        require(live.getBlockState(new BlockPos(cx * 16, y, BASE_Z * 16)).is(Blocks.GOLD_BLOCK),
                                "Section/height boundary mismatch " + y);
                        observed++;
                    }
                } else if (scenario.startsWith("cold")) {
                    require(live.getBlockState(new BlockPos(cx * 16 + 1, 80, BASE_Z * 16 + 1)).is(Blocks.GOLD_BLOCK), "Cold write missing");
                    observed++;
                } else {
                    for (int index = 0; index < 4096; index++) {
                        int x = index & 15, y = 80 + (index >> 8), z = (index >> 4) & 15;
                        boolean changed = index % 31 == 0 || x == 0 && z == 0 && ((y & 15) == 0 || (y & 15) == 15);
                        var actual = live.getBlockState(new BlockPos(cx * 16 + x, y, BASE_Z * 16 + z));
                        var expected = changed ? Blocks.GOLD_BLOCK.defaultBlockState()
                                : scenario.equals("local") ? PaletteFixture.block.defaultBlockState().setValue(VARIANT, index % 4)
                                : scenario.equals("mixed") && index % 3 == 0 ? Blocks.OAK_LOG.defaultBlockState()
                                : PaletteFixture.block.defaultBlockState().setValue(VARIANT, index % 1024);
                        require(actual == expected, "Independent native palette oracle mismatch " + index);
                        if (changed) observed++;
                    }
                }
            }
            require(applied == observed && proposals == observed, "Exact proposal/world count mismatch");
        }
        return Map.of("scenario", scenario, "applied", applied, "proposals", proposals, "observed", observed,
                "stats", stats, "order", List.copyOf(startedOrder), "cancelled", cancelled, "violations", violations.get(),
                "paletteBits", List.copyOf(paletteBits));
    }

    private static String failure(CompletableFuture<?> future) {
        try {
            future.join();
            return "";
        } catch (Exception failure) {
            var trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            return trace.toString();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            violations.incrementAndGet();
            throw new AssertionError(message);
        }
    }

    private static final class Run {
        private final EditActorContext context;
        private final List<PreparedChunkEdit> proposals = new java.util.concurrent.CopyOnWriteArrayList<>();
        private CompletableFuture<List<CommitReceipt>> future;
        private Run(EditActorContext context) { this.context = context; }
    }

}
