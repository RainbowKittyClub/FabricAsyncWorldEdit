package com.fastasyncworldedit.testharness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.queue.implementation.SingleThreadQueueExtent;
import com.fastasyncworldedit.core.queue.implementation.blocks.CharSetBlocks;
import com.fastasyncworldedit.fabric.compat.EditIntegration;
import com.fastasyncworldedit.fabric.edit.CommitReceipt;
import com.fastasyncworldedit.fabric.edit.EditActorContext;
import com.fastasyncworldedit.fabric.edit.EditDelta;
import com.fastasyncworldedit.fabric.edit.FabricEdits;
import com.fastasyncworldedit.fabric.edit.PreparedChunkEdit;
import com.google.gson.Gson;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.history.change.BlockChange;
import com.sk89q.worldedit.history.change.Change;
import com.sk89q.worldedit.history.changeset.ArrayListHistory;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Installed-jar consumer of the production queue API; barriers never perform world mutations. */
public final class QueueContractProbe implements ModInitializer, EditIntegration {
    private static volatile FabricEdits service;
    private static volatile String scenario = "idle";
    private static volatile EditActorContext context;
    private static volatile SingleThreadQueueExtent queue;
    private static volatile CompletableFuture<List<CommitReceipt>> operation;
    private static volatile CompletableFuture<Void> prepare;
    private static volatile CompletableFuture<Void> reconcile;
    private static volatile CompletableFuture<Void> audit;
    private static volatile CompletableFuture<Void> historyGate;
    private static volatile CoreFuture recursive;
    private static volatile ArrayListHistory history;
    private static final AtomicInteger prepared = new AtomicInteger();
    private static final AtomicInteger processed = new AtomicInteger();
    private static final AtomicInteger reconciled = new AtomicInteger();
    private static final AtomicInteger recorded = new AtomicInteger();
    private static final AtomicInteger closed = new AtomicInteger();
    private static final AtomicInteger completionCount = new AtomicInteger();
    private static final AtomicInteger historyEntered = new AtomicInteger();
    private static final AtomicInteger postprocessed = new AtomicInteger();
    private static final AtomicInteger saturated = new AtomicInteger();
    private static CountDownLatch poolRelease;
    private static List<java.util.concurrent.Future<?>> poolTasks = List.of();
    private static volatile boolean mainRejected;
    private static volatile List<CommitReceipt> receipts = List.of();
    private static volatile String failureText = "";

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("fawequeue").then(Commands.argument("arguments", StringArgumentType.greedyString())
                        .executes(command -> {
                            try {
                                String result = control(command.getSource().getServer(), StringArgumentType.getString(command, "arguments"));
                                command.getSource().sendSuccess(() -> Component.literal(result), false);
                                return 1;
                            } catch (Throwable failure) {
                                command.getSource().sendFailure(Component.literal(failure.toString()));
                                return 0;
                            }
                        }))));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (scenario.equals("server-stop")) {
                prepare.complete(null);
            }
            if (poolRelease != null) poolRelease.countDown();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (scenario.equals("server-stop")) {
                try {
                    operation.handle((value, failure) -> null).get(10, java.util.concurrent.TimeUnit.SECONDS);
                    try {
                        service.shutdownDrain().toCompletableFuture().get(15, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (java.util.concurrent.ExecutionException failure) {
                        require(PublicationProbe.expectedPublishedShutdown()
                                && failure.toString().contains("Shutdown interrupted finalization"),
                                "Only the additional S5 published-stop fixture may fail this drain");
                    }
                    require(service.pendingOperations() == 0, "Shutdown releases admitted producer operations");
                    require(operation.isCompletedExceptionally(), "Shutdown must fail the outstanding operation");
                    require(completionCount.get() == 1, "Shutdown completion delivered once");
                    write("shutdown", state());
                } catch (Exception failure) {
                    write("shutdown", Map.of("failure", failure.toString()));
                }
            }
        });
    }

    // Control barriers only; the normal asynchronous queue API owns every tested edit.
    private static String control(MinecraftServer server, String args) {
        String[] words = args.split(" ");
        switch (words[0]) {
            case "start" -> start(server, words[1], words[2]);
            case "release" -> {
                switch (words[1]) {
                    case "prepare" -> prepare.complete(null);
                    case "reconcile" -> reconcile.complete(null);
                    case "history" -> historyGate.complete(null);
                    case "audit" -> audit.complete(null);
                    case "recursive" -> {
                        if (scenario.equals("recursive-failure")) recursive.completeExceptionally(new IllegalStateException("recursive injected"));
                        else recursive.complete(null);
                    }
                    case "pool" -> poolRelease.countDown();
                    default -> throw new IllegalArgumentException(args);
                }
            }
            case "main" -> {
                require(queue != null, "Producer has supplied the real queue");
                try {
                    queue.flush();
                    throw new AssertionError("Server thread synchronously flushed a dependent queue");
                } catch (IllegalStateException expected) {
                    mainRejected = expected.getMessage().contains("asynchronous Fabric edit API");
                }
            }
            case "biome" -> {
                var chunk = server.overworld().getChunkSource().getChunkNow(65, 64);
                require(chunk != null, "Biome oracle requires a loaded chunk");
                return new Gson().toJson(Map.of("target", chunk.getNoiseBiome(261, 20, 258).unwrapKey().orElseThrow().identifier().toString(),
                        "neighbor", chunk.getNoiseBiome(260, 20, 258).unwrapKey().orElseThrow().identifier().toString()));
            }
            case "status" -> { }
            default -> throw new IllegalArgumentException(args);
        }
        return new Gson().toJson(state());
    }

    // Select the actual adapter through FabricEdits, then use ordinary core extent methods.
    private static void start(MinecraftServer server, String name, String selected) {
        require(operation == null || operation.isDone(), "Previous queue operation must finish");
        for (var task : poolTasks) require(task.isDone(), "Previous saturation task still alive");
        scenario = selected;
        queue = null;
        receipts = List.of();
        failureText = "";
        mainRejected = false;
        for (AtomicInteger counter : List.of(prepared, processed, reconciled, recorded, closed,
                completionCount, historyEntered, postprocessed, saturated)) counter.set(0);
        prepare = new CompletableFuture<>();
        reconcile = scenario.equals("held-reconcile") ? new CompletableFuture<>() : CompletableFuture.completedFuture(null);
        audit = scenario.equals("held-audit") ? new CompletableFuture<>() : CompletableFuture.completedFuture(null);
        historyGate = scenario.equals("held-history") ? new CompletableFuture<>() : CompletableFuture.completedFuture(null);
        recursive = new CoreFuture();
        history = new ArrayListHistory() {
            @Override
            public void add(Change change) {
                historyEntered.incrementAndGet();
                historyGate.join(); // Test-only external barrier, released by the independent server thread.
                if (scenario.equals("history-failure")) throw new IllegalStateException("history injected");
                super.add(change);
            }
        };
        ServerPlayer player = java.util.Objects.requireNonNull(server.getPlayerList().getPlayerByName(name));
        context = new EditActorContext(player.getUUID(), name, EditActorContext.Source.API, UUID.randomUUID(),
                null, null, "minecraft:overworld", SideEffectSet.none(), new EditActorContext.Cancellation());
        if (scenario.equals("saturated")) {
            var executor = Fawe.instance().getQueueHandler().getBlockingExecutor();
            int workers = executor.getMaximumPoolSize();
            require(workers <= 64, "Unexpected FAWE worker count");
            poolRelease = new CountDownLatch(1);
            List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                tasks.add(executor.submit(() -> {
                    saturated.incrementAndGet();
                    try { poolRelease.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                }));
            }
            poolTasks = List.copyOf(tasks);
        }
        int targetX = scenario.equals("history-failure") || scenario.equals("server-stop") ? 1012 : 1044;
        List<BlockVector2> footprint = scenario.equals("history-failure") || scenario.equals("server-stop")
                ? List.of(BlockVector2.at(63, 64))
                : List.of(BlockVector2.at(65, 64), BlockVector2.at(64, 64), BlockVector2.at(65, 65));
        service = FabricEdits.get(server);
        operation = service.submit(context, footprint, history, extent -> {
                    queue = extent;
                    extent.setProcessor(new Processor(false));
                    extent.setPostProcessor(new Processor(true));
                    if (scenario.startsWith("recursive")) {
                        CoreFuture root = new CoreFuture();
                        root.complete(recursive);
                        extent.submitTaskUnchecked(() -> root);
                    }
                    if (scenario.equals("immediate-failure")) {
                        extent.submitTaskUnchecked(() -> { throw new IllegalStateException("immediate injected"); });
                    }
                    if (scenario.equals("finalizer-failure")) {
                        var holder = com.fastasyncworldedit.core.queue.implementation.chunk.ChunkHolder.newInstance();
                        holder.init(extent, targetX >> 4, 1032 >> 4);
                        holder.setBlock(targetX & 15, 80, 8, BlockTypes.GOLD_BLOCK.getDefaultState());
                        extent.submitTaskUnchecked(() -> holder.call(extent, holder.getChunkSet().createCopy(), () -> {
                            throw new IllegalStateException("finalizer injected");
                        }));
                    } else if (scenario.equals("nbt")) {
                        var nbt = com.sk89q.worldedit.fabric.internal.NBTConverter.toNative(extent.getFullBlock(targetX, 80, 1032).getNbt());
                        nbt.putString("CustomName", "queue-after");
                        extent.tile(targetX, 80, 1032, com.fastasyncworldedit.core.nbt.FaweCompoundTag.of(
                                com.sk89q.worldedit.fabric.internal.NBTConverter.fromNative(nbt)));
                    } else if (scenario.equals("biome")) {
                        extent.setBiome(targetX, 80, 1032, com.sk89q.worldedit.world.biome.BiomeTypes.DESERT);
                    } else {
                        extent.setBlock(targetX, 80, 1032, BlockTypes.GOLD_BLOCK.getDefaultState());
                    }
                    if (scenario.equals("full-queue")) {
                        extent.setTargetSize(0);
                        extent.setBlock(1028, 80, 1032, BlockTypes.GOLD_BLOCK.getDefaultState());
                        extent.setBlock(1044, 80, 1048, BlockTypes.GOLD_BLOCK.getDefaultState());
                    }
                }).toCompletableFuture();
        operation.whenComplete((value, failure) -> {
            completionCount.incrementAndGet();
            if (value != null) receipts = value;
            if (failure != null) {
                Throwable cause = failure;
                while (!(cause instanceof FabricEdits.OperationFailure) && cause.getCause() != null) cause = cause.getCause();
                require(cause instanceof FabricEdits.OperationFailure, "API failure retains publication facts");
                var failed = (FabricEdits.OperationFailure) cause;
                require(failed.context().equals(context), "Exceptional completion retains explicit identity");
                receipts = failed.publishedReceipts();
            }
            try {
                receipts.clear();
                throw new AssertionError("Receipt list was mutable");
            } catch (UnsupportedOperationException expected) { }
            failureText = failure == null ? "" : failure.toString();
            write(selected, state());
        });
    }

    @Override
    public String id() { return "s3-queue-probe"; }

    @Override
    public Set<EditDelta.Kind> capabilities() { return Set.of(EditDelta.Kind.BLOCK, EditDelta.Kind.BIOME); }

    @Override
    public CompletionStage<Reservation> prepare(PreparedChunkEdit proposal) {
        if (PublicationProbe.owns(proposal)) {
            return PublicationProbe.prepare(proposal);
        }
        if (PreparationProbe.owns(proposal)) {
            return PreparationProbe.prepare(proposal);
        }
        require(proposal.context().equals(context), "Production adapter carries the explicit admitted context");
        prepared.incrementAndGet();
        require(!Fawe.isMainThread(), "Preparation is detached");
        return prepare.thenApply(unused -> new Reservation() {
            @Override
            public boolean authorize(ServerLevel level, ServerPlayer player, EditActorContext actor, EditDelta delta) {
                require(level.getServer().isSameThread(), "Authorization is owner-thread");
                require(actor.equals(context) && player.getUUID().equals(context.actorId()), "Explicit actor reached adapter");
                return !scenario.equals("deny");
            }
            @Override
            public void beforePublication(ServerLevel level, List<EditDelta> authorized) { }
            @Override
            public CompletionStage<Void> reconcile(ServerLevel level, CommitReceipt receipt) {
                require(receipt.context().equals(context), "Receipt retains admitted identity");
                reconciled.incrementAndGet();
                return reconcile;
            }
            @Override
            public CompletionStage<Void> record(CommitReceipt receipt) {
                recorded.incrementAndGet();
                return audit;
            }
            @Override
            public void close(ServerLevel level) { closed.incrementAndGet(); }
            @Override
            public void discard() { closed.incrementAndGet(); }
        });
    }

    // Upstream processors must affect the set actually dispatched by ChunkHolder.call.
    private static final class Processor implements IBatchProcessor {
        private final boolean post;
        private Processor(boolean post) { this.post = post; }
        @Override
        public IChunkSet processSet(IChunk chunk, IChunkGet get, IChunkSet set) {
            processed.incrementAndGet();
            if (scenario.equals("veto")) return null;
            if (scenario.equals("replacement")) {
                IChunkSet replacement = CharSetBlocks.newInstance();
                replacement.setBlock(4, 80, 8, BlockTypes.DIRT.getDefaultState());
                return replacement;
            }
            if (scenario.equals("processor-failure")) throw new IllegalStateException("processor injected");
            return set;
        }
        @Override
        public void validateCommitCompletion() { require(post, "Only postprocessor consumes committed data"); }
        @Override
        public CompletionStage<Void> completeCommit(IChunk chunk, IChunkGet before, IChunkSet applied) {
            postprocessed.incrementAndGet();
            if (scenario.equals("post-failure")) throw new IllegalStateException("postprocessor injected");
            if (scenario.equals("deny") || scenario.equals("veto") || scenario.equals("conflict")) {
                require(applied.isEmpty(), "Denied/veto/stale proposals never enter completion buffers");
                require(before.tiles().isEmpty(), "Unchanged tile history excluded");
            }
            return CompletableFuture.completedFuture(null);
        }
        @Override
        public Extent construct(Extent child) { return child; }
    }

    // Observe upstream history independently of the coordinator's applied counter.
    private static Map<String, Object> state() {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (history != null && operation != null && operation.isDone()) {
            history.forwardIterator().forEachRemaining(change -> {
                if (change instanceof BlockChange block) {
                    blocks.add(Map.of("x", block.position().x(), "y", block.position().y(), "z", block.position().z(),
                            "before", block.previous().toImmutableState().getAsString(), "after", block.current().toImmutableState().getAsString(),
                            "beforeNbt", String.valueOf(block.previous().getNbt()), "afterNbt", String.valueOf(block.current().getNbt())));
                } else if (change instanceof com.sk89q.worldedit.history.change.BiomeChange3D biome) {
                    blocks.add(Map.of("x", biome.position().x(), "y", biome.position().y(), "z", biome.position().z(),
                            "before", biome.previous().id(), "after", biome.current().id()));
                }
            });
        }
        return Map.ofEntries(Map.entry("scenario", scenario), Map.entry("complete", operation != null && operation.isDone()),
                Map.entry("exceptional", operation != null && operation.isCompletedExceptionally()), Map.entry("failure", failureText),
                Map.entry("prepared", prepared.get()), Map.entry("processed", processed.get()), Map.entry("reconciled", reconciled.get()),
                Map.entry("recorded", recorded.get()), Map.entry("closed", closed.get()), Map.entry("completions", completionCount.get()),
                Map.entry("historyEntered", historyEntered.get()), Map.entry("history", blocks), Map.entry("postprocessed", postprocessed.get()),
                Map.entry("mainRejected", mainRejected), Map.entry("saturated", saturated.get()),
                Map.entry("poolDone", poolTasks.stream().allMatch(java.util.concurrent.Future::isDone)),
                Map.entry("poolSize", poolTasks.size()),
                Map.entry("applied", receipts.stream().mapToInt(value -> value.applied().size()).sum()),
                Map.entry("outcomes", receipts.stream().map(value -> value.outcome().name()).toList()));
    }

    // Atomic evidence publication avoids readers observing a partially written result.
    private static void write(String suffix, Object data) {
        String prefix = System.getProperty("fawe.test.lifecycle");
        if (prefix == null) return;
        try {
            Path target = Path.of(prefix + "-queue-" + suffix + ".json");
            Path temporary = Path.of(target + ".pending");
            Files.writeString(temporary, new Gson().toJson(data));
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    // Assertions are fatal test evidence, never permission fallbacks.
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class CoreFuture extends CompletableFuture<CoreFuture> { }
}
