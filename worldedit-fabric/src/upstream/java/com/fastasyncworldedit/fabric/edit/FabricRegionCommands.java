package com.fastasyncworldedit.fabric.edit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.history.changeset.ArrayListHistory;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BlockState;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Opt-in production commands for bounded cuboid edits with disk history and no neighbor callbacks. */
public final class FabricRegionCommands {
    private static final Map<UUID, String> permissions = new ConcurrentHashMap<>();
    // Owner thread: each player's operations still running, so cancel, disconnect and stop reach them.
    private static final Map<UUID, List<Running>> running = new java.util.HashMap<>();
    private static long completed;

    private FabricRegionCommands() { }

    /** A running operation's shared token and, once asked, why it was cancelled. */
    private static final class Running {
        private final EditActorContext.Cancellation cancellation;
        private volatile String reason;

        private Running(EditActorContext.Cancellation cancellation) {
            this.cancellation = cancellation;
        }

        // Units not yet published are refused at preparation or publication; published ones stay recorded.
        private void cancel(String why) {
            if (reason == null) reason = why;
            cancellation.cancel();
        }
    }

    /** Register a separate explicit mode without changing ordinary WNA commands. */
    public static void initialize() {
        // Registered before FabricEdits' own stop hook, so stopping operations carry their reason.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server ->
                cancelAll(null, "server stopping"));
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                server.execute(() -> cancelAll(handler.getPlayer().getUUID(), "operator disconnected")));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            if (!Boolean.getBoolean("fawe.experimental.coordinated")) return;
            dispatcher.register(Commands.literal("fawe-coordinated")
                    .then(Commands.literal("cancel").executes(command -> {
                        ServerPlayer player = command.getSource().getPlayerOrException();
                        int cancelled = cancelAll(player.getUUID(), "cancelled by " + player.getGameProfile().name());
                        command.getSource().sendSuccess(() -> Component.literal(cancelled == 0
                                ? "No coordinated edit is running."
                                : "Cancelling " + cancelled + " coordinated edit(s); changes already written stay"
                                        + " recorded and undoable."), false);
                        return cancelled;
                    }))
                    .then(Commands.literal("set").then(Commands.argument("state", StringArgumentType.word())
                            .executes(command -> submit(command.getSource(), null, StringArgumentType.getString(command, "state")))))
                    .then(Commands.literal("replace").then(Commands.argument("before", StringArgumentType.word())
                            .then(Commands.argument("state", StringArgumentType.word()).executes(command -> submit(
                                    command.getSource(), StringArgumentType.getString(command, "before"),
                                    StringArgumentType.getString(command, "state")))))));
        });
    }

    /** Recheck the command's current permission alongside integration authorization for every unit. */
    public static boolean authorized(EditActorContext context, ServerPlayer player) {
        if (context.source() != EditActorContext.Source.PLAYER) return true;
        String node = permissions.get(context.operationId());
        return node != null && FabricAdapter.adaptPlayer(player).hasPermission(node);
    }

    /** Owner-thread count of fully completed production commands, not submission acknowledgements. */
    public static long completed() { return completed; }

    // Owner thread. A null player cancels everyone's operations.
    private static int cancelAll(UUID player, String why) {
        int count = 0;
        for (var entry : running.entrySet()) {
            if (player != null && !player.equals(entry.getKey())) continue;
            for (Running operation : entry.getValue()) {
                operation.cancel(why);
                count++;
            }
        }
        return count;
    }

    // The command thread only captures immutable geometry and state identifiers for preparation.
    private static int submit(CommandSourceStack source, String matching, String replacement) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            var actor = FabricAdapter.adaptPlayer(player);
            String node = matching == null ? "worldedit.region.set" : "worldedit.region.replace";
            if (!actor.hasPermission(node)) throw new UnsupportedOperationException("Missing permission: " + node);
            var session = WorldEdit.getInstance().getSessionManager().get(actor);
            var world = actor.getWorld();
            var selection = session.getSelection(world);
            if (!(selection instanceof CuboidRegion) || session.getMask() != null || session.getSourceMask() != null
                    || session.getTransform() != null) {
                throw new UnsupportedOperationException("Coordinated commands require a cuboid without session masks or transforms");
            }
            var limit = actor.getLimit();
            long volume = selection.getVolume();
            if (volume > limit.MAX_CHANGES.get() || volume > limit.MAX_CHECKS.get()
                    || (session.getBlockChangeLimit() >= 0 && volume > session.getBlockChangeLimit())
                    || limit.INVENTORY_MODE != 0 || limit.MAX_HISTORY < 64
                    || (limit.DISALLOWED_BLOCKS != null && !limit.DISALLOWED_BLOCKS.isEmpty())
                    || (limit.REMAP_PROPERTIES != null && !limit.REMAP_PROPERTIES.isEmpty())
                    || (limit.STRIP_NBT != null && !limit.STRIP_NBT.isEmpty())) {
                throw new UnsupportedOperationException("This limit/inventory policy has no coordinated command adapter");
            }
            var parser = new com.sk89q.worldedit.extension.input.ParserContext();
            parser.setActor(actor);
            parser.setWorld(world);
            parser.setSession(session);
            parser.setRestricted(true);
            var parsed = WorldEdit.getInstance().getBlockFactory().parseFromInput(replacement, parser);
            if (parsed.hasNbtData()) throw new UnsupportedOperationException("Command NBT requires an explicit API payload");
            String state = parsed.toImmutableState().getAsString();
            String before = matching == null ? null : WorldEdit.getInstance().getBlockFactory()
                    .parseFromInput(matching, parser).toImmutableState().getAsString();
            var min = selection.getMinimumPoint();
            var max = selection.getMaximumPoint();
            long units = (long) ((max.x() >> 4) - (min.x() >> 4) + 1)
                    * ((max.z() >> 4) - (min.z() >> 4) + 1) * ((max.y() >> 4) - (min.y() >> 4) + 1);
            if (units > 64) throw new IllegalArgumentException("Coordinated command is limited to 64 sections");
            var service = FabricEdits.get(source.getServer());
            var history = new ArrayListHistory();
            UUID operationId = UUID.randomUUID();
            var context = new EditActorContext(player.getUUID(), actor.getName(), EditActorContext.Source.PLAYER,
                    operationId, null, null, player.level().dimension().identifier().toString(), SideEffectSet.none(),
                    new EditActorContext.Cancellation());
            var operation = new Running(context.cancellation());
            running.computeIfAbsent(player.getUUID(), key -> new ArrayList<>()).add(operation);
            var tally = new Tally();
            // Sequential sections keep overlapping snapshots fresh; each section can independently reprepare.
            CompletableFuture<List<CommitReceipt>> chain = CompletableFuture.completedFuture(new ArrayList<>());
            for (int cx = min.x() >> 4; cx <= max.x() >> 4; cx++) {
                for (int cz = min.z() >> 4; cz <= max.z() >> 4; cz++) {
                    for (int sy = min.y() >> 4; sy <= max.y() >> 4; sy++) {
                        int x0 = Math.max(min.x(), cx << 4), x1 = Math.min(max.x(), (cx << 4) + 15);
                        int z0 = Math.max(min.z(), cz << 4), z1 = Math.min(max.z(), (cz << 4) + 15);
                        int y0 = Math.max(min.y(), sy << 4), y1 = Math.min(max.y(), (sy << 4) + 15);
                        BlockVector2 chunk = BlockVector2.at(cx, cz);
                        var unitContext = new EditActorContext(context.actorId(), context.actorName(), context.source(),
                                UUID.randomUUID(), operationId, null, context.dimension(), context.sideEffects(), context.cancellation());
                        chain = chain.thenCompose(receipts -> {
                            permissions.put(unitContext.operationId(), node);
                            try {
                                return service.submitRetrying(unitContext, chunk, history, queue -> {
                            BlockState block = BlockState.get(state);
                            for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
                                if (before == null || queue.getBlock(x, y, z).getAsString().equals(before)) queue.setBlock(x, y, z, block);
                            }
                        }).thenApply(next -> {
                            receipts.addAll(next);
                            tally.add(next);
                            if (next.stream().anyMatch(receipt -> receipt.outcome() != CommitReceipt.Outcome.COMPLETE)) {
                                throw new IllegalStateException("Coordinated command stopped after partial, denied or conflicting work");
                            }
                            return receipts;
                        }).whenComplete((value, failure) -> permissions.remove(unitContext.operationId()))
                                .exceptionally(failure -> { throw new UnitFailure(chunk, failure); });
                            } catch (Throwable failure) {
                                permissions.remove(unitContext.operationId());
                                return CompletableFuture.<List<CommitReceipt>>failedFuture(new UnitFailure(chunk, failure));
                            }
                        }).toCompletableFuture();
                    }
                }
            }
            chain.whenComplete((receipts, failure) -> {
                // Written to disk before it is remembered or reported, partial results included; a
                // closed disk history is found again after a restart even if the session never saw it.
                com.sk89q.worldedit.history.changeset.ChangeSet remembered = history;
                Throwable unwritten = null;
                if (com.fastasyncworldedit.core.configuration.Settings.settings().HISTORY.USE_DISK && history.size() > 0) {
                    try {
                        remembered = HistoryProvenance.persist(world, actor.getUniqueId(), history,
                                HistoryProvenance.originOf(history));
                    } catch (java.io.IOException | RuntimeException writeFailure) {
                        unwritten = writeFailure;
                    }
                }
                var durable = remembered;
                var historyFailure = unwritten;
                source.getServer().execute(() -> finish(source, session, actor, world, limit, operationId,
                        history, durable, historyFailure, failure, operation, tally, player.getUUID()));
            });
            source.sendSuccess(() -> Component.literal("Coordinated edit admitted: " + operationId), false);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("Coordinated edit refused: " + failure.getMessage()));
            return 0;
        }
    }

    // Owner thread: remember the history (disk when written) and report the true outcome.
    private static void finish(CommandSourceStack source, com.sk89q.worldedit.LocalSession session,
            com.sk89q.worldedit.fabric.FabricPlayer actor, com.sk89q.worldedit.world.World world,
            com.fastasyncworldedit.core.limit.FaweLimit limit, UUID operationId, ArrayListHistory history,
            com.sk89q.worldedit.history.changeset.ChangeSet durable, Throwable historyFailure, Throwable failure,
            Running operation, Tally tally, UUID player) {
        permissions.remove(operationId);
        var mine = running.get(player);
        if (mine != null && mine.remove(operation) && mine.isEmpty()) running.remove(player);
        session.remember(actor, world, durable, limit);
        if (historyFailure != null) {
            source.sendFailure(Component.literal("Coordinated edit history could not be written to disk; "
                    + history.size() + " changes stay undoable only until restart: " + rootMessage(historyFailure)));
        }
        String kind = durable == history ? "memory" : "disk";
        if (failure == null) {
            completed++;
            source.sendSuccess(() -> Component.literal("Coordinated edit complete: " + history.size()
                    + " changes" + tally.unchanged() + " (" + kind + " history; no neighbor callbacks)."), false);
        } else {
            tally.add(failure);
            // A refusal is clean only when no unit recorded or may have made changes.
            CommitReceipt unknown = receiptMatching(failure,
                    receipt -> receipt.outcome() == CommitReceipt.Outcome.APPLIED_UNKNOWN);
            CommitReceipt refusal = history.size() == 0 && unknown == null
                    ? receiptMatching(failure, FabricRegionCommands::isPreWriteRefusal) : null;
            UnitFailure unpublished = history.size() == 0 && unknown == null && refusal == null
                    && publishedNothing(failure) ? unitFailure(failure) : null;
            String cancelled = operation.reason;
            if (unknown == null && cancelled != null) {
                // Whatever the next unit then reported, the operation ended because it was cancelled.
                source.sendFailure(Component.literal("Coordinated edit cancelled (" + cancelled + ") "
                        + (history.size() == 0 ? "before any change was written"
                                : "after " + history.size() + " recorded changes (" + kind + " history)")
                        + tally.unchanged() + tally.refused() + "; nothing after that was written."));
            } else if (refusal != null) {
                source.sendFailure(Component.literal("Coordinated edit REFUSED for chunk "
                        + refusal.chunkX() + "," + refusal.chunkZ()
                        + "; no changes were written: " + refusalReason(refusal)));
            } else if (unpublished != null) {
                // The operation itself reports publishing nothing, so it failed in preparation.
                source.sendFailure(Component.literal("Coordinated edit REFUSED for chunk "
                        + unpublished.chunk.x() + "," + unpublished.chunk.z()
                        + "; no changes were written: " + rootMessage(unpublished)));
            } else if (unknown != null) {
                source.sendFailure(Component.literal("Coordinated edit failed: chunk "
                        + unknown.chunkX() + "," + unknown.chunkZ() + " was written but could not be"
                        + " read back, so it is quarantined; at least " + history.size()
                        + " recorded changes: " + rootMessage(failure)));
            } else {
                UnitFailure unit = unitFailure(failure);
                source.sendFailure(Component.literal("Coordinated edit failed after " + history.size()
                        + " recorded changes" + (unit == null ? "" : " in chunk " + unit.chunk.x() + ","
                        + unit.chunk.z()) + tally.refused() + ": " + rootMessage(failure)));
            }
        }
    }

    /** Counts across every receipt of one operation, including those a failure carries. */
    private static final class Tally {
        private final java.util.Set<CommitReceipt> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        private long noOp;
        private long denied;
        private long conflicts;

        synchronized void add(List<CommitReceipt> receipts) {
            for (CommitReceipt receipt : receipts) {
                if (!seen.add(receipt)) continue;
                noOp += receipt.noOp();
                denied += receipt.denied();
                if (receipt.outcome() == CommitReceipt.Outcome.CONFLICT) conflicts++;
            }
        }

        synchronized void add(Throwable failure) {
            for (Throwable current = failure; current != null; current = current.getCause()) {
                if (current instanceof FabricCommitCoordinator.CommitFailure commit) add(List.of(commit.receipt()));
                if (current instanceof FabricEdits.OperationFailure operation) add(operation.publishedReceipts());
            }
        }

        synchronized String unchanged() {
            return noOp == 0 ? "" : ", " + noOp + " unchanged";
        }

        synchronized String refused() {
            String counts = (denied == 0 ? "" : denied + " denied")
                    + (denied != 0 && conflicts != 0 ? ", " : "")
                    + (conflicts == 0 ? "" : conflicts + " conflicting unit(s)");
            return counts.isEmpty() ? "" : " [" + counts + "]";
        }
    }

    // First receipt carried by the failure chain that matches; partial/post-write failures keep their wording.
    private static CommitReceipt receiptMatching(Throwable failure, Predicate<CommitReceipt> test) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof FabricCommitCoordinator.CommitFailure commit) {
                CommitReceipt receipt = commit.receipt();
                if (test.test(receipt)) return receipt;
            }
            if (current instanceof FabricEdits.OperationFailure operation) {
                for (CommitReceipt receipt : operation.publishedReceipts()) {
                    if (test.test(receipt)) return receipt;
                }
            }
        }
        return null;
    }

    /** A unit's failure tagged with its chunk, so a refusal before any receipt can still name it. */
    private static final class UnitFailure extends RuntimeException {
        private final BlockVector2 chunk;

        private UnitFailure(BlockVector2 chunk, Throwable cause) {
            super("Coordinated unit for chunk " + chunk.x() + "," + chunk.z() + " failed", cause);
            this.chunk = chunk;
        }
    }

    // Positive evidence only: an operation failure with no published receipt and no commit failure.
    private static boolean publishedNothing(Throwable failure) {
        boolean operation = false;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof FabricCommitCoordinator.CommitFailure) return false;
            if (current instanceof FabricEdits.OperationFailure failed) {
                if (!failed.publishedReceipts().isEmpty()) return false;
                operation = true;
            }
        }
        return operation;
    }

    private static UnitFailure unitFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof UnitFailure unit) return unit;
        }
        return null;
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }

    private static boolean isPreWriteRefusal(CommitReceipt receipt) {
        return receipt.outcome() == CommitReceipt.Outcome.FAILED_BEFORE_WRITE && receipt.applied().isEmpty();
    }

    // Keep the integration's chunk/position and reason, dropping exception/wrapper prefixes.
    private static String refusalReason(CommitReceipt receipt) {
        String text = receipt.failure();
        int separator = text.indexOf(": ");
        if (separator > 0 && text.substring(0, separator).chars().noneMatch(Character::isWhitespace)) {
            text = text.substring(separator + 2);
        }
        String wrapper = FabricCommitCoordinator.INTEGRATION_REFUSAL_PREFIX;
        return text.startsWith(wrapper) ? text.substring(wrapper.length()) : text;
    }
}
