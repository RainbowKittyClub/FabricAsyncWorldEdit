package com.fastasyncworldedit.testharness;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.Gson;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import com.fastasyncworldedit.core.extent.processor.EmptyBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.queue.IQueueExtent;
import com.fastasyncworldedit.core.queue.implementation.blocks.CharSetBlocks;
import com.fastasyncworldedit.core.queue.implementation.chunk.ChunkHolder;
import com.fastasyncworldedit.fabric.compat.EditIntegration;
import com.fastasyncworldedit.fabric.compat.EditIntegrations;
import com.fastasyncworldedit.fabric.edit.CommitReceipt;
import com.fastasyncworldedit.fabric.edit.EditActorContext;
import com.fastasyncworldedit.fabric.edit.EditDelta;
import com.fastasyncworldedit.fabric.edit.EditValue;
import com.fastasyncworldedit.fabric.edit.FabricCommitCoordinator;
import com.fastasyncworldedit.fabric.edit.FabricGetBlocks;
import com.fastasyncworldedit.fabric.edit.FabricStateMapping;
import com.fastasyncworldedit.fabric.edit.FabricWorldAccess;
import com.fastasyncworldedit.fabric.edit.FrozenNbt;
import com.fastasyncworldedit.fabric.edit.PreparedChunkEdit;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BlockTypes;

/** Non-shipped API consumer; setup/mutations used to force races are supplied independently by RCON. */
public final class CommitContractProbe implements ModInitializer {
    private ExecutorService workers;
    private FabricCommitCoordinator coordinator;
    private FabricStateMapping mapping;
    private FabricWorldAccess access;
    private CompletableFuture<Void> preparation;
    private CompletableFuture<Void> history;
    private CompletableFuture<Void> audit;
    private CompletionStage<List<CommitReceipt>> completion;
    private CompletionStage<Void> shutdown;
    private FabricCommitCoordinator.Operation operation;
    private final AtomicInteger prepared = new AtomicInteger();
    private final AtomicInteger recorded = new AtomicInteger();
    private final AtomicInteger reconciled = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();
    private String scenario;
    private int checks;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("fawecontract").then(Commands.argument("arguments", StringArgumentType.greedyString())
                        .executes(command -> {
                            String[] arguments = StringArgumentType.getString(command, "arguments").split(" ");
                            String answer = dispatch(command.getSource().getServer(), arguments);
                            command.getSource().sendSuccess(() -> Component.literal(answer), false);
                            return 1;
                        }))));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (coordinator != null) {
                coordinator.tick();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (coordinator != null) {
                coordinator.stop();
                require(coordinator.pendingCount() == 0, "No pending commits at orderly stop");
                coordinator = null;
            }
            if (workers != null) {
                workers.shutdown();
                workers = null;
            }
        });
    }

    // All commands are test-control operations; edits themselves go through the public coordinator.
    private String dispatch(MinecraftServer server, String[] arguments) {
        if (arguments[0].equals("start")) {
            start(server, arguments[1], arguments[2]);
        } else if (arguments[0].equals("release")) {
            switch (arguments[1]) {
                case "prepare" -> preparation.complete(null);
                case "history" -> history.complete(null);
                case "audit" -> audit.complete(null);
                default -> throw new IllegalArgumentException("Unknown barrier");
            }
        } else if (arguments[0].equals("stop")) {
            shutdown = coordinator.stop();
            require(shutdown == coordinator.stop(), "Repeated stop retains its pending drain");
        } else if (!arguments[0].equals("status")) {
            throw new IllegalArgumentException("Unknown probe action");
        }
        String drained = shutdown == null ? "not-stopped" : !shutdown.toCompletableFuture().isDone() ? "pending"
                : shutdown.toCompletableFuture().isCompletedExceptionally() ? "failed" : "complete";
        return new Gson().toJson(Map.of("scenario", scenario, "drain", drained,
                "complete", completion.toCompletableFuture().isDone(), "published", operation.publishedReceipts().size(),
                "recorded", recorded.get(), "reconciled", reconciled.get(), "closed", closed.get(),
                "pending", coordinator.pendingCount(), "bytes", coordinator.retainedBytes(), "prepared", prepared.get()));
    }

    // Capture a real loaded world and hold detached preparation behind a deterministic barrier.
    private void start(MinecraftServer server, String playerName, String selected) {
        require(completion == null || completion.toCompletableFuture().isDone(), "Prior case must finish");
        if (mapping == null) {
            mapping = new FabricStateMapping(server);
            access = new FabricWorldAccess(server, mapping);
            workers = Executors.newFixedThreadPool(4);
            coreBoundary();
            frozenValues();
        }
        scenario = selected;
        shutdown = null;
        prepared.set(0);
        recorded.set(0);
        reconciled.set(0);
        closed.set(0);
        preparation = new CompletableFuture<>();
        history = new CompletableFuture<>();
        audit = new CompletableFuture<>();
        if (!selected.equals("held")) {
            history.complete(null);
            audit.complete(null);
        }
        if (selected.equals("shutdown-audit")) {
            audit = new CompletableFuture<>();
        }
        var player = java.util.Objects.requireNonNull(server.getPlayerList().getPlayerByName(playerName));
        var context = new EditActorContext(player.getUUID(), playerName, EditActorContext.Source.API,
                UUID.randomUUID(), null, null, "minecraft:overworld", SideEffectSet.none(),
                new EditActorContext.Cancellation());
        // Polymer quarantines a chunk whose finalization fails, so that case runs outside watched chunks.
        int chunkZ = selected.equals("reconcile-failure") ? 80 : 64;
        var snapshot = FabricGetBlocks.capture(server.overworld(), 65, chunkZ, mapping, null);
        require(snapshot.getBlock(4, 80, 8) != null, "Snapshot sample exists");
        char[] external = snapshot.load(5);
        char original = external[0];
        external[0] = BlockTypes.DIAMOND_BLOCK.getDefaultState().getOrdinalChar();
        require(snapshot.load(5)[0] == original, "Section arrays cannot mutate snapshot");
        require(snapshot.getCopy(0) == snapshot, "Immutable copy semantics");
        List<EditDelta> deltas = new ArrayList<>();
        EditValue before = snapshot.value(4, 80, 8);
        EditValue after = new EditValue("minecraft:gold_block", null);
        if (selected.equals("nbt")) {
            var nbt = before.nbt().thaw();
            nbt.putString("CustomName", "contract-new");
            after = new EditValue(before.id(), FrozenNbt.capture(nbt));
        }
        deltas.add(new EditDelta(EditDelta.Kind.BLOCK, 1044, 80, chunkZ * 16 + 8, "", before, after));
        PreparedChunkEdit proposal = new PreparedChunkEdit(context, 65, chunkZ, mapping.generation(), snapshot.dependencies(), deltas);
        deltas.clear();
        require(proposal.writes().size() == 1, "Proposal owns its list");
        // Installed integrations must take part, as in production, or validate() rejects them.
        List<EditIntegration> providers = new ArrayList<>(EditIntegrations.discover());
        providers.add(provider(server, context));
        coordinator = new FabricCommitCoordinator(server, access, List.copyOf(providers), workers, 8, 64L << 20);
        operation = coordinator.admit(context, receipt -> {
            require(!server.isSameThread(), "History is detached");
            require(receipt.context() == context, "Explicit identity survives thread hop");
            return history;
        });
        coordinator.submitCommit(proposal);
        if (selected.equals("cancel")) {
            context.cancellation().cancel();
        }
        completion = operation.seal();
        completion.whenComplete((receipts, failure) -> {
            List<CommitReceipt> facts = operation.publishedReceipts();
            if (facts.stream().anyMatch(receipt -> receipt.outcome() == CommitReceipt.Outcome.CONFLICT)) {
                var mismatches = new ArrayList<String>();
                for (var dependency : proposal.reads()) {
                    var chunk = access.loadedChunk(server.overworld(), dependency.x() >> 4, dependency.z() >> 4);
                    long currentRevision = ((com.fastasyncworldedit.fabric.edit.ChunkRevision) chunk).worldedit$editRevision();
                    var actual = access.read(server.overworld(), dependency.kind(), dependency.x(), dependency.y(), dependency.z(), dependency.key());
                    if (currentRevision != dependency.revision() || !java.util.Objects.equals(actual, dependency.value())) {
                        mismatches.add(dependency.x() + "," + dependency.y() + "," + dependency.z()
                                + " revision=" + dependency.revision() + "->" + currentRevision
                                + " before=" + dependency.value() + " actual=" + actual
                                + " beforeNbt=" + (dependency.value().nbt() == null ? "null" : dependency.value().nbt().encoded())
                                + " actualNbt=" + (actual.nbt() == null ? "null" : actual.nbt().encoded()));
                        if (mismatches.size() >= 4) {
                            break;
                        }
                    }
                }
                write(Map.of("mismatches", mismatches), "-conflict-detail");
            }
            require(coordinator.pendingCount() == 0, "Completion follows budget release");
            write(Map.of("scenario", selected, "checks", checks, "exceptional", failure != null,
                    "applied", facts.stream().mapToInt(value -> value.applied().size()).sum(),
                    "outcomes", facts.stream().map(value -> value.outcome().name()).toList(),
                    "recorded", recorded.get(), "closed", closed.get(), "actor", context.actorId().toString(),
                    "pending", coordinator.pendingCount(), "bytes", coordinator.retainedBytes()));
        });
    }

    // This barrier provider tests completion semantics, not OPAC policy or Polymer rendering.
    private EditIntegration provider(MinecraftServer server, EditActorContext context) {
        return new EditIntegration() {
            @Override
            public String id() {
                return "contract-probe";
            }

            @Override
            public Set<EditDelta.Kind> capabilities() {
                return Set.of(EditDelta.Kind.BLOCK);
            }

            @Override
            public CompletionStage<Reservation> prepare(PreparedChunkEdit proposal) {
                require(!server.isSameThread(), "Preparation is detached");
                require(proposal.context() == context, "Preparation retains explicit identity");
                prepared.incrementAndGet();
                return preparation.thenApply(unused -> new Reservation() {
                    @Override
                    public boolean authorize(net.minecraft.server.level.ServerLevel level,
                            net.minecraft.server.level.ServerPlayer player, EditActorContext actor, EditDelta delta) {
                        require(server.isSameThread(), "Authorization owns the server thread");
                        require(player.getUUID().equals(actor.actorId()), "Current player matches authority");
                        return !scenario.equals("deny");
                    }

                    @Override
                    public void beforePublication(net.minecraft.server.level.ServerLevel level, List<EditDelta> deltas) {
                        require(server.isSameThread(), "Prepublication ownership");
                    }

                    @Override
                    public CompletionStage<Void> reconcile(net.minecraft.server.level.ServerLevel level, CommitReceipt receipt) {
                        require(server.isSameThread(), "Reconciliation ownership");
                        reconciled.incrementAndGet();
                        return scenario.equals("reconcile-failure")
                                ? CompletableFuture.failedFuture(new IllegalStateException("Injected reconciliation failure"))
                                : CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<Void> record(CommitReceipt receipt) {
                        require(!server.isSameThread(), "Recording is detached even after owner-thread barrier release");
                        recorded.incrementAndGet();
                        return audit;
                    }

                    @Override
                    public void close(net.minecraft.server.level.ServerLevel level) {
                        require(server.isSameThread(), "Release ownership");
                        closed.incrementAndGet();
                    }

                    @Override
                    public void discard() {
                        closed.incrementAndGet();
                        if (scenario.equals("shutdown-discard-failure")) {
                            throw new IllegalStateException("Injected late reservation release failure");
                        }
                    }
                });
            }
        };
    }

    // Verify the real core ChunkHolder adapter argument and veto rather than a duplicate algorithm.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void coreBoundary() {
        AtomicReference<IChunkSet> observed = new AtomicReference<>();
        AtomicReference<IChunkSet> processed = new AtomicReference<>();
        AtomicInteger finalizers = new AtomicInteger();
        IChunkGet get = (IChunkGet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {IChunkGet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "call" -> {
                        observed.set((IChunkSet) args[1]);
                        ((Runnable) args[2]).run();
                        yield null;
                    }
                    case "setCreateCopy" -> 0;
                    case "trim" -> false;
                    default -> null;
                });
        IQueueExtent extent = (IQueueExtent) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {IQueueExtent.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCachedGet" -> get;
                    case "getPostProcessor" -> EmptyBatchProcessor.getInstance();
                    case "processSet" -> processed.get();
                    default -> null;
                });
        IChunkSet original = CharSetBlocks.newInstance();
        original.setBlock(0, 80, 0, BlockTypes.STONE.getDefaultState());
        IChunkSet replacement = CharSetBlocks.newInstance();
        replacement.setBlock(0, 80, 0, BlockTypes.GOLD_BLOCK.getDefaultState());
        processed.set(replacement);
        ChunkHolder holder = ChunkHolder.newInstance();
        holder.init(extent, 65, 64);
        holder.call(extent, original, finalizers::incrementAndGet);
        require(observed.get() == replacement, "Core publishes processor replacement, not original buffer");
        observed.set(null);
        processed.set(null);
        holder.call(extent, original, finalizers::incrementAndGet);
        require(observed.get() == null && finalizers.get() == 2, "Core veto skips adapter and finalizes exactly once");
    }

    // Adversarial nested payload mutation must not alter an already handed-off value.
    private void frozenValues() {
        var input = new net.minecraft.nbt.CompoundTag();
        var nested = new net.minecraft.nbt.CompoundTag();
        nested.putIntArray("array", new int[] {1, 2, 3});
        input.put("nested", nested);
        FrozenNbt frozen = FrozenNbt.capture(input);
        nested.putString("later", "changed");
        require(!frozen.thaw().toString().contains("later"), "Deep NBT capture");
        frozen.thaw().putString("outside", "changed");
        require(!frozen.thaw().contains("outside"), "NBT accessor returns a fresh copy");
    }

    // Keep failed evidence; no assertion is converted into a skip.
    private synchronized void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    // A private runner property selects the evidence path for this non-shipped observer.
    private void write(Map<String, Object> result) {
        write(result, "");
    }

    // Preserve diagnostic data separately from the assertion result.
    private void write(Map<String, Object> result, String suffix) {
        try {
            Path resultPath = Path.of(System.getProperty("fawe.test.lifecycle") + "-" + scenario + suffix + ".json");
            Path pendingPath = resultPath.resolveSibling(resultPath.getFileName() + ".pending");
            Files.writeString(pendingPath, new Gson().toJson(result), StandardOpenOption.CREATE_NEW);
            Files.move(pendingPath, resultPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
