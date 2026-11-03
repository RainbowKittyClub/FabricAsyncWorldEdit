package com.fastasyncworldedit.testharness;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.github.quiltservertools.ledger.actions.BlockChangeActionType;
import com.github.quiltservertools.ledger.api.BatchAck;
import com.github.quiltservertools.ledger.api.LedgerApi;
import com.github.quiltservertools.ledger.api.ProducerBatch;
import com.github.quiltservertools.ledger.api.ProducerBatchException;
import com.google.gson.Gson;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** `faweaudit`: independent Ledger producer probes for the S8 suite; asynchronous results are polled. */
public final class LedgerAuditCommands {
    /** Set by the LedgerAppendCapture mixin: the bridge's most recent batch, as sent. */
    public static volatile ProducerBatch lastBatch;
    private static final Map<String, Object> RESULTS = new ConcurrentHashMap<>();
    private static final Gson GSON = new Gson();

    private LedgerAuditCommands() {
    }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("faweaudit").then(Commands.argument("arguments", StringArgumentType.greedyString())
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
        LedgerApi api = LedgerApi.get();
        switch (words[0]) {
            case "ready" -> {
                String refusal = api.validateProducerAction(dummy(new BlockPos(0, 0, 0), "minecraft:stone"));
                return GSON.toJson(Map.of("ready", refusal == null, "reason", String.valueOf(refusal),
                        "limit", api.producerBatchLimit()));
            }
            case "captured" -> {
                ProducerBatch batch = lastBatch;
                if (batch == null) return GSON.toJson(Map.of("captured", false));
                return GSON.toJson(Map.of("captured", true, "producer", batch.producer(),
                        "operation", batch.operationId().toString(),
                        "sequences", batch.entries().stream().map(ProducerBatch.Entry::sequence).toList()));
            }
            // Redeliver the bridge's last batch unchanged: identical retries must store nothing.
            case "redeliver" -> {
                ProducerBatch batch = lastBatch;
                if (batch == null) throw new IllegalStateException("No captured batch");
                track("redeliver", api.appendBatch(batch).thenApply(LedgerAuditCommands::ack));
                return GSON.toJson(Map.of("started", "redeliver"));
            }
            // Ledger's own contract on a private producer: first commit, identical retry, conflict.
            case "contract" -> {
                UUID operation = UUID.randomUUID();
                var first = batch(operation, "minecraft:stone");
                var conflicting = batch(operation, "minecraft:dirt");
                track("contract", api.appendBatch(first).thenCompose(one -> api.appendBatch(first)
                        .thenCompose(two -> api.appendBatch(conflicting).handle((ignored, failure) ->
                                Map.of("first", ack(one), "retry", ack(two), "conflict", reason(failure))))
                        .thenCompose(result -> api.producerReceipts("fawe-test", operation)
                                .thenApply(receipts -> Map.of("operation", operation.toString(), "result", result,
                                        "receipts", receipts.size())))));
                return GSON.toJson(Map.of("started", "contract", "operation", operation.toString()));
            }
            // fill <batches> <size>: private batches that occupy Ledger's producer queue while it is stalled.
            case "fill" -> {
                int batches = Integer.parseInt(words[1]);
                int size = Integer.parseInt(words[2]);
                List<CompletableFuture<Object>> sent = new java.util.ArrayList<>();
                for (int index = 0; index < batches; index++) {
                    List<ProducerBatch.Entry> entries = new java.util.ArrayList<>(size);
                    for (int sequence = 0; sequence < size; sequence++) {
                        entries.add(new ProducerBatch.Entry(sequence,
                                dummy(new BlockPos(10_000 + index, 80, 10_000 + sequence), "minecraft:stone")));
                    }
                    sent.add(api.appendBatch(new ProducerBatch("fawe-test", UUID.randomUUID(), entries))
                            .<Object>thenApply(LedgerAuditCommands::ack)
                            .exceptionally(failure -> Map.of("failure", reason(failure))));
                }
                track("fill", CompletableFuture.allOf(sent.toArray(CompletableFuture[]::new))
                        .thenApply(ignored -> sent.stream().map(CompletableFuture::join).toList()));
                return GSON.toJson(Map.of("started", "fill", "batches", batches));
            }
            case "result" -> {
                Object result = RESULTS.get(words[1]);
                return GSON.toJson(result == null ? Map.of("pending", true) : result);
            }
            // bigchest x y z chars [linesep]: a chest with that much text. Plain text trips FAWE's 64 KiB
            // snapshot cap; U+2028 is one character in NBT but a six-character escape in JSON extra data.
            case "bigchest" -> {
                String fill = words.length > 5 && words[5].equals("linesep") ? " " : "x";
                BlockPos pos = new BlockPos(Integer.parseInt(words[1]), Integer.parseInt(words[2]),
                        Integer.parseInt(words[3]));
                level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
                var chest = (ChestBlockEntity) level.getBlockEntity(pos);
                // Minecraft cannot save one NBT string over 65,535 bytes, so split the payload.
                CompoundTag data = new CompoundTag();
                int remaining = Integer.parseInt(words[4]);
                for (int part = 0; remaining > 0; part++, remaining -= 30_000) {
                    data.putString("blob" + part, fill.repeat(Math.min(remaining, 30_000)));
                }
                ItemStack paper = new ItemStack(Items.PAPER);
                paper.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
                chest.setItem(0, paper);
                chest.setChanged();
                return GSON.toJson(Map.of("placed", pos.toShortString()));
            }
            default -> throw new IllegalArgumentException("Unknown faweaudit command: " + words[0]);
        }
    }

    private static void track(String key, CompletableFuture<?> future) {
        RESULTS.remove(key);
        future.whenComplete((value, failure) -> RESULTS.put(key, failure == null ? value
                : Map.of("failure", String.valueOf(reason(failure)))));
    }

    private static ProducerBatch batch(UUID operation, String block) {
        return new ProducerBatch("fawe-test", operation, List.of(
                new ProducerBatch.Entry(0, dummy(new BlockPos(9000, 80, 9000), block)),
                new ProducerBatch.Entry(1, dummy(new BlockPos(9001, 80, 9000), block))));
    }

    private static BlockChangeActionType dummy(BlockPos pos, String block) {
        var action = new BlockChangeActionType();
        action.setPos(pos);
        action.setWorld(Identifier.parse("minecraft:overworld"));
        action.setObjectIdentifier(Identifier.parse(block));
        action.setOldObjectIdentifier(Identifier.parse("minecraft:air"));
        action.setSourceName("fawe-test");
        action.setTimestamp(java.time.Instant.ofEpochSecond(1_800_000_000L));
        return action;
    }

    private static Map<String, Object> ack(BatchAck ack) {
        return Map.of("receipt", ack.receiptId(), "accepted", ack.accepted(), "duplicates", ack.duplicates());
    }

    private static String reason(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ProducerBatchException refused) return refused.reason().name();
        }
        return failure == null ? "none" : String.valueOf(failure);
    }
}
