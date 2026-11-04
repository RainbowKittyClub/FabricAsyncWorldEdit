package com.fastasyncworldedit.fabric.edit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

import com.fastasyncworldedit.core.history.DiskStorageHistory;
import com.google.common.collect.MapMaker;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.history.change.BiomeChange3D;
import com.sk89q.worldedit.history.change.BlockChange;
import com.sk89q.worldedit.history.change.Change;
import com.sk89q.worldedit.history.changeset.ArrayListHistory;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import com.sk89q.worldedit.world.World;

/**
 * Who made a history entry, so its undo and redo can name it as their parent. Kept beside the
 * history while it is in memory and, for disk history, in its {@code <index>.prov} file, so the link
 * survives a restart. Missing or mixed identities stay explicitly unknown.
 */
public final class HistoryProvenance {
    private static final String FORMAT = "fawe-history-provenance-v1";
    private static final ConcurrentMap<ChangeSet, Origin> ORIGINS = new MapMaker().weakKeys().makeMap();
    static final Origin UNKNOWN = new Origin(null, null);

    private HistoryProvenance() {
    }

    /** The originating operation and actor; both null when unknown. */
    public record Origin(UUID operationId, UUID actorId) { }

    /** In memory first; a history reloaded from disk reads its provenance file once. */
    public static Origin originOf(ChangeSet history) {
        Origin known = ORIGINS.get(history);
        if (known != null) {
            return known;
        }
        if (history instanceof DiskStorageHistory disk && disk.getProvenanceFile().isFile()) {
            Origin read = read(disk.getProvenanceFile().toPath());
            ORIGINS.putIfAbsent(history, read);
            return read;
        }
        return UNKNOWN;
    }

    /**
     * Record that changes from {@code origin} went into {@code history}. The first origin of an empty
     * history names it; any later, different origin makes it unknown. Call with the history locked.
     */
    static void note(ChangeSet history, Origin origin, boolean emptyBefore) {
        Origin previous = ORIGINS.get(history);
        Origin next = previous == null
                ? (emptyBefore && origin.operationId() != null ? origin : UNKNOWN)
                : (previous.equals(origin) ? previous : UNKNOWN);
        if (next.equals(previous)) {
            return;
        }
        ORIGINS.put(history, next);
        if (history instanceof DiskStorageHistory disk) {
            write(disk.getProvenanceFile().toPath(), next);
        }
    }

    /**
     * Copy a completed in-memory history into disk history named by {@code origin} and close it, so
     * it is undoable after a restart even if the server stops before the session remembers it.
     */
    public static DiskStorageHistory persist(World world, UUID owner, ArrayListHistory history, Origin origin)
            throws IOException {
        DiskStorageHistory disk = new DiskStorageHistory(world, owner);
        synchronized (history) {
            note(disk, origin, true);
            for (Iterator<Change> changes = history.forwardIterator(); changes.hasNext(); ) {
                Change change = changes.next();
                if (change instanceof BlockChange block) {
                    var pos = block.getPosition();
                    disk.add(pos.x(), pos.y(), pos.z(), block.getPrevious(), block.getCurrent());
                } else if (change instanceof BiomeChange3D biome) {
                    var pos = biome.getPosition();
                    disk.addBiomeChange(pos.x(), pos.y(), pos.z(), biome.getPrevious(), biome.getCurrent());
                } else {
                    throw new IOException("No disk history codec for " + change.getClass().getSimpleName());
                }
            }
        }
        disk.close();
        return disk;
    }

    private static Origin read(Path file) {
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!FORMAT.equals(json.get("format").getAsString()) || json.get("operation").isJsonNull()) {
                return UNKNOWN;
            }
            return new Origin(UUID.fromString(json.get("operation").getAsString()),
                    json.get("actor").isJsonNull() ? null : UUID.fromString(json.get("actor").getAsString()));
        } catch (IOException | RuntimeException unreadable) {
            return UNKNOWN;
        }
    }

    // Written whole and moved into place, so a crash leaves the old record or the new one.
    private static void write(Path file, Origin origin) {
        JsonObject json = new JsonObject();
        json.addProperty("format", FORMAT);
        json.addProperty("operation", origin.operationId() == null ? null : origin.operationId().toString());
        json.addProperty("actor", origin.actorId() == null ? null : origin.actorId().toString());
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            throw new IllegalStateException("History provenance could not be written to " + file, failure);
        }
    }
}
