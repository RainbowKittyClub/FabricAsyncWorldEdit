package com.fastasyncworldedit.fabric.compat.ledger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.fastasyncworldedit.fabric.edit.EditDelta;
import com.fastasyncworldedit.fabric.edit.EditValue;
import com.fastasyncworldedit.fabric.edit.FrozenNbt;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import org.apache.logging.log4j.Logger;

import net.minecraft.nbt.TagParser;

/**
 * Bounded durable journal of audited units, one directory per world. A unit's intent file is
 * written before publication and its receipt file after; both go once Ledger acknowledges the rows.
 * Every file operation runs on one daemon thread, so a unit's writes and removal stay ordered.
 */
final class LedgerOutbox {
    static final String FORMAT = "fawe-ledger-outbox-v1";
    static final int MAX_UNITS = 64;
    static final long MAX_BYTES = 128L << 20;
    private static final Logger LOGGER = LogManagerCompat.getLogger();
    private static final String INTENT = ".intent.json";
    private static final String RECEIPT = ".receipt.json";
    private static final String TEMP = ".tmp";

    /** A unit as journaled; receipt is null until publication has been recorded. */
    record Unit(UUID id, LedgerPayloads.Origin origin, int chunkX, int chunkZ, List<EditDelta> deltas,
            Receipt receipt) {
        Unit {
            deltas = List.copyOf(deltas);
        }
    }

    /** What was applied: Ledger sequence and index into the intent's deltas, and the row timestamp. */
    record Receipt(Instant timestamp, List<Applied> applied) {
        Receipt {
            applied = List.copyOf(applied);
        }
    }

    record Applied(long sequence, int index) {
    }

    private final Path directory;
    private final Path quarantine;
    private final ThreadPoolExecutor executor;
    // Guarded by this: bytes on disk per unit, counted against the bounds.
    private final Map<UUID, Long> sizes = new HashMap<>();
    private long bytes;

    LedgerOutbox(Path directory) {
        this.directory = directory;
        this.quarantine = directory.resolve("quarantine");
        this.executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "fawe-ledger-outbox");
            thread.setDaemon(true);
            return thread;
        });
        executor.allowCoreThreadTimeOut(true);
    }

    Path directory() {
        return directory;
    }

    /** Durably journal an intent; fails, writing nothing, when the outbox is full or the disk refuses. */
    CompletableFuture<Void> writeIntent(Unit unit) {
        return CompletableFuture.runAsync(() -> {
            byte[] data = intentJson(unit).toString().getBytes(StandardCharsets.UTF_8);
            reserve(unit.id(), data.length);
            try {
                write(directory.resolve(unit.id() + INTENT), data);
            } catch (IOException | RuntimeException failure) {
                release(unit.id());
                throw new IllegalStateException("Ledger audit outbox could not record the intent: " + failure.getMessage(),
                        failure);
            }
        }, executor);
    }

    /** Durably journal what was applied for an already journaled intent. */
    CompletableFuture<Void> writeReceipt(UUID id, Receipt receipt) {
        return CompletableFuture.runAsync(() -> {
            byte[] data = receiptJson(id, receipt).toString().getBytes(StandardCharsets.UTF_8);
            try {
                write(directory.resolve(id + RECEIPT), data);
            } catch (IOException failure) {
                throw new IllegalStateException("Ledger audit outbox could not record the receipt: " + failure.getMessage(),
                        failure);
            }
            synchronized (this) {
                sizes.computeIfPresent(id, (key, size) -> size + data.length);
                bytes += data.length;
            }
        }, executor);
    }

    /** Forget a finished unit; a leftover file is harmless, since recovery is idempotent. */
    CompletableFuture<Void> remove(UUID id) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.deleteIfExists(directory.resolve(id + RECEIPT));
                Files.deleteIfExists(directory.resolve(id + INTENT));
            } catch (IOException failure) {
                LOGGER.warn("Ledger audit outbox could not remove finished unit {}: {}", id, failure.getMessage());
            } finally {
                release(id);
            }
        }, executor);
    }

    /** Set a unit aside for an operator, with the reason beside it; it never retries by itself. */
    CompletableFuture<Void> quarantine(UUID id, String reason) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(quarantine);
                for (String suffix : List.of(INTENT, RECEIPT)) {
                    Path source = directory.resolve(id + suffix);
                    if (Files.exists(source)) {
                        Files.move(source, quarantine.resolve(id + suffix), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                write(quarantine.resolve(id + ".reason.txt"), (reason + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException failure) {
                LOGGER.warn("Ledger audit outbox could not quarantine unit {}: {}", id, failure.getMessage());
            } finally {
                release(id);
            }
            LOGGER.warn("Ledger audit outbox quarantined unit {} in {}: {}", id, quarantine, reason);
        }, executor);
    }

    /** Read every journaled unit at startup; unreadable ones are quarantined, stray temp files dropped. */
    CompletableFuture<List<Unit>> load() {
        return CompletableFuture.supplyAsync(() -> {
            List<Unit> units = new ArrayList<>();
            if (!Files.isDirectory(directory)) {
                return units;
            }
            List<UUID> unreadable = new ArrayList<>();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(TEMP)) {
                        Files.deleteIfExists(file);
                    } else if (name.endsWith(INTENT)) {
                        UUID id = UUID.fromString(name.substring(0, name.length() - INTENT.length()));
                        try {
                            Unit unit = read(id);
                            // Already on disk: counted against the bounds, never refused by them.
                            synchronized (this) {
                                sizes.put(id, Files.size(file));
                                bytes += sizes.get(id);
                            }
                            units.add(unit);
                        } catch (RuntimeException | IOException failure) {
                            LOGGER.warn("Ledger audit outbox unit {} is unreadable: {}", id, failure.toString());
                            unreadable.add(id);
                        }
                    }
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Ledger audit outbox could not be read: " + failure.getMessage(), failure);
            }
            unreadable.forEach(id -> quarantine(id, "unreadable outbox unit"));
            return units;
        }, executor);
    }

    private synchronized void reserve(UUID id, long size) {
        if (sizes.size() >= MAX_UNITS || bytes + size > MAX_BYTES) {
            throw new IllegalStateException("Ledger audit outbox is full (" + sizes.size() + " units, " + bytes
                    + " bytes); audited edits wait for Ledger to catch up");
        }
        sizes.put(id, size);
        bytes += size;
    }

    private synchronized void release(UUID id) {
        Long size = sizes.remove(id);
        if (size != null) {
            bytes -= size;
        }
    }

    // Temp file, fsync, atomic rename, then fsync the directory so the rename itself survives a crash.
    private void write(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + TEMP);
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            Files.deleteIfExists(temp);
            throw failure;
        }
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            try (FileChannel parent = FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
                parent.force(true);
            }
        }
    }

    private Unit read(UUID id) throws IOException {
        JsonObject intent = JsonParser.parseString(Files.readString(directory.resolve(id + INTENT))).getAsJsonObject();
        if (!FORMAT.equals(intent.get("format").getAsString()) || !id.toString().equals(intent.get("id").getAsString())) {
            throw new IllegalStateException("not a " + FORMAT + " intent for " + id);
        }
        JsonObject origin = intent.getAsJsonObject("origin");
        List<EditDelta> deltas = new ArrayList<>();
        for (JsonElement element : intent.getAsJsonArray("deltas")) {
            JsonObject delta = element.getAsJsonObject();
            deltas.add(new EditDelta(EditDelta.Kind.valueOf(delta.get("kind").getAsString()), delta.get("x").getAsInt(),
                    delta.get("y").getAsInt(), delta.get("z").getAsInt(), delta.get("key").getAsString(),
                    value(delta.get("before")), value(delta.get("after"))));
        }
        Receipt receipt = null;
        Path receiptFile = directory.resolve(id + RECEIPT);
        if (Files.exists(receiptFile)) {
            JsonObject stored = JsonParser.parseString(Files.readString(receiptFile)).getAsJsonObject();
            if (!FORMAT.equals(stored.get("format").getAsString()) || !id.toString().equals(stored.get("id").getAsString())) {
                throw new IllegalStateException("not a " + FORMAT + " receipt for " + id);
            }
            List<Applied> applied = new ArrayList<>();
            for (JsonElement element : stored.getAsJsonArray("applied")) {
                JsonObject entry = element.getAsJsonObject();
                int index = entry.get("index").getAsInt();
                if (index < 0 || index >= deltas.size()) {
                    throw new IllegalStateException("receipt index " + index + " is outside the intent");
                }
                applied.add(new Applied(entry.get("sequence").getAsLong(), index));
            }
            receipt = new Receipt(Instant.parse(stored.get("timestamp").getAsString()), applied);
        }
        return new Unit(id, new LedgerPayloads.Origin(origin.get("dimension").getAsString(),
                uuid(origin.get("actorId")), string(origin.get("actorName")),
                UUID.fromString(origin.get("operation").getAsString()), uuid(origin.get("parent")),
                origin.get("source").getAsString()),
                intent.get("chunkX").getAsInt(), intent.get("chunkZ").getAsInt(), deltas, receipt);
    }

    private static JsonObject intentJson(Unit unit) {
        JsonObject intent = new JsonObject();
        intent.addProperty("format", FORMAT);
        intent.addProperty("id", unit.id().toString());
        JsonObject origin = new JsonObject();
        origin.addProperty("dimension", unit.origin().dimension());
        origin.addProperty("actorId", unit.origin().actorId() == null ? null : unit.origin().actorId().toString());
        origin.addProperty("actorName", unit.origin().actorName());
        origin.addProperty("operation", unit.origin().operation().toString());
        origin.addProperty("parent", unit.origin().parent() == null ? null : unit.origin().parent().toString());
        origin.addProperty("source", unit.origin().source());
        intent.add("origin", origin);
        intent.addProperty("chunkX", unit.chunkX());
        intent.addProperty("chunkZ", unit.chunkZ());
        JsonArray deltas = new JsonArray();
        for (EditDelta delta : unit.deltas()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("kind", delta.kind().name());
            entry.addProperty("x", delta.x());
            entry.addProperty("y", delta.y());
            entry.addProperty("z", delta.z());
            entry.addProperty("key", delta.key());
            entry.add("before", json(delta.before()));
            entry.add("after", json(delta.after()));
            deltas.add(entry);
        }
        intent.add("deltas", deltas);
        return intent;
    }

    private static JsonObject receiptJson(UUID id, Receipt receipt) {
        JsonObject stored = new JsonObject();
        stored.addProperty("format", FORMAT);
        stored.addProperty("id", id.toString());
        stored.addProperty("timestamp", receipt.timestamp().toString());
        JsonArray applied = new JsonArray();
        for (Applied entry : receipt.applied()) {
            JsonObject item = new JsonObject();
            item.addProperty("sequence", entry.sequence());
            item.addProperty("index", entry.index());
            applied.add(item);
        }
        stored.add("applied", applied);
        return stored;
    }

    private static JsonElement json(EditValue value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject side = new JsonObject();
        side.addProperty("id", value.id());
        side.addProperty("nbt", value.nbt() == null ? null : value.nbt().encoded());
        return side;
    }

    private static EditValue value(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        JsonObject side = element.getAsJsonObject();
        String nbt = string(side.get("nbt"));
        try {
            return new EditValue(side.get("id").getAsString(),
                    nbt == null ? null : FrozenNbt.capture(TagParser.parseCompoundFully(nbt)));
        } catch (CommandSyntaxException failure) {
            throw new IllegalStateException("stored NBT does not parse: " + failure.getMessage(), failure);
        }
    }

    private static String string(JsonElement element) {
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static UUID uuid(JsonElement element) {
        String text = string(element);
        return text == null ? null : UUID.fromString(text);
    }
}
