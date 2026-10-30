package com.fastasyncworldedit.fabric.edit;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/** Ref-counted bounded loading tickets; scheduling uses the verified non-waiting radius API. */
public final class FabricChunkTickets {
    private static TicketType ticketType;
    private static final Map<ServerLevel, FabricChunkTickets> OWNERS = new java.util.IdentityHashMap<>();
    private final ServerLevel level;
    private final int limit;
    private final Map<Long, Entry> entries = new HashMap<>();
    private boolean stopped;
    private long acquired;
    private long released;

    /** Register before registry freeze, without creating or touching a world. */
    public static void register() {
        if (ticketType == null) {
            ticketType = Registry.register(BuiltInRegistries.TICKET_TYPE,
                    Identifier.fromNamespaceAndPath("worldedit", "coordinated_edit"),
                    new TicketType(0, TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));
        }
    }

    /** Construct one manager per dimension and coordinator. */
    public FabricChunkTickets(ServerLevel level, int limit) {
        this.level = level;
        this.limit = limit;
        if (ticketType == null || limit < 1) {
            throw new IllegalStateException("Ticket type must be registered and budget positive");
        }
        owner();
        synchronized (OWNERS) {
            if (OWNERS.putIfAbsent(level, this) != null) {
                throw new IllegalStateException("This dimension already has a ticket owner");
            }
        }
    }

    /** Reserve without blocking; the lease must remain owned through required finalization. */
    public Lease acquire(int x, int z) {
        owner();
        if (stopped) {
            throw new RejectedExecutionException("Chunk loading is stopped");
        }
        ChunkPos position = new ChunkPos(x, z);
        Entry entry = entries.get(position.pack());
        if (entry == null) {
            if (entries.size() >= limit) {
                throw new RejectedExecutionException("Chunk ticket budget exhausted");
            }
            entry = new Entry(position);
            entries.put(position.pack(), entry);
            Entry captured = entry;
            acquired++;
            try {
                level.getChunkSource().addTicketAndLoadWithRadius(ticketType, position, 0)
                        .whenComplete((unused, failure) -> level.getServer().execute(() -> {
                            if (captured.removed) {
                                captured.loaded.completeExceptionally(new RejectedExecutionException("Load lease released"));
                            } else if (failure != null) {
                                captured.loaded.completeExceptionally(failure);
                            } else if (level.getChunkSource().getChunkNow(x, z) == null) {
                                captured.loaded.completeExceptionally(new IllegalStateException("Chunk load returned no FULL chunk"));
                            } else {
                                captured.loaded.complete(null);
                            }
                        }));
            } catch (Throwable failure) {
                level.getChunkSource().removeTicketWithRadius(ticketType, position, 0);
                released++;
                entries.remove(position.pack());
                throw failure;
            }
        }
        entry.references++;
        return new Lease(entry);
    }

    /** Stop and remove each owned underlying ticket exactly once. */
    public void stop() {
        owner();
        stopped = true;
        for (Entry entry : entries.values()) {
            level.getChunkSource().removeTicketWithRadius(ticketType, entry.position, 0);
            released++;
            entry.removed = true;
            entry.loaded.completeExceptionally(new RejectedExecutionException("Chunk loading stopped"));
        }
        entries.clear();
        synchronized (OWNERS) {
            OWNERS.remove(level, this);
        }
    }

    /** Count owned underlying tickets, not lease references. */
    public int size() {
        owner();
        return entries.size();
    }

    /** Total underlying tickets acquired; shared leases do not add another ticket. */
    public long acquired() {
        owner();
        return acquired;
    }

    /** Total underlying tickets removed, including load failures and shutdown. */
    public long released() {
        owner();
        return released;
    }

    /** One reference whose idempotent close never removes another operation's ticket. */
    public final class Lease implements AutoCloseable {
        private final Entry entry;
        private boolean closed;

        // Only acquire grants lease ownership.
        private Lease(Entry entry) {
            this.entry = entry;
        }

        /** Completion of FULL load without handing workers a live chunk. */
        public CompletionStage<Void> loaded() {
            return entry.loaded.minimalCompletionStage();
        }

        @Override
        public void close() {
            owner();
            if (closed) {
                return;
            }
            closed = true;
            if (--entry.references == 0 && !entry.removed) {
                entry.removed = true;
                entries.remove(entry.position.pack(), entry);
                level.getChunkSource().removeTicketWithRadius(ticketType, entry.position, 0);
                released++;
                entry.loaded.completeExceptionally(new RejectedExecutionException("Last load lease released"));
            }
        }
    }

    // The owner thread alone mutates ticket accounting.
    private void owner() {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Chunk ticket ownership requires the server thread");
        }
    }

    // Vanilla tickets are keyed by type/level/position, so concurrent leases share a refcount.
    private static final class Entry {
        private final ChunkPos position;
        private final CompletableFuture<Void> loaded = new CompletableFuture<>();
        private int references;
        private boolean removed;

        // No live chunk is retained by the completion future.
        private Entry(ChunkPos position) {
            this.position = position;
        }
    }
}
