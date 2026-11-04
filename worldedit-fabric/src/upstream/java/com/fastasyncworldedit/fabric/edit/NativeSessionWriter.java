package com.fastasyncworldedit.fabric.edit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BaseBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * A bound WorldEdit session's writes, committed one chunk per unit. Sealed units queue here; a
 * unit is admitted once its chunk has no unit in flight, so other chunks fill the heartbeats while
 * a chunk's earlier unit relights. The session reads its own pending writes before the world. Only
 * writes without cross-cell callbacks belong here.
 */
public final class NativeSessionWriter {
    private static final int UNIT_CELLS = 4096;
    private static final long UNIT_BYTES = 8L << 20;
    private static final int OPEN_CHUNKS = 16;
    // Sealed and not yet finished: the edit thread waits beyond either bound.
    private static final int OUTSTANDING_UNITS = 16;
    private static final long OUTSTANDING_BYTES = 32L << 20;
    // Admitted to the coordinator at once, a share of its 32-unit admission.
    private static final int ADMITTED_UNITS = 4;

    /**
     * The session's side of each unit. {@link #server} and {@link #history} are asked on the editing
     * thread, the rest on the server thread.
     */
    public interface Host {
        MinecraftServer server();

        /** A fresh unit context with these side effects, cancelled with the session. */
        EditActorContext unitContext(SideEffectSet effects);

        /** Register the operator's command authority for this unit, if the session carries one. */
        void authorize(UUID operation);

        void forget(UUID operation);

        /** The history attached now; a write is recorded where it was when the write was made. */
        Consumer<CommitReceipt> history();

        /** Cancel every remaining unit of the session. */
        void cancel();

        /** The exception the session's caller sees for this failure. */
        RuntimeException stop(Throwable failure);
    }

    /** A written value as the session reads it back before it is published. */
    public record Pending(EditValue value, BaseBlock block) { }

    private record Entry(Pending pending, Buffer owner) { }

    // One chunk's cells; blocks and biomes are keyed apart, since their positions may coincide.
    private static final class Buffer {
        private final int chunkX;
        private final int chunkZ;
        private final SideEffectSet effects;
        private final Consumer<CommitReceipt> history;
        private final LinkedHashMap<Long, FabricEdits.NativeCell> blocks = new LinkedHashMap<>();
        private final LinkedHashMap<Long, FabricEdits.NativeCell> biomes = new LinkedHashMap<>();
        private long bytes;

        private Buffer(int chunkX, int chunkZ, SideEffectSet effects, Consumer<CommitReceipt> history) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.effects = effects;
            this.history = history;
        }

        private long chunk() {
            return chunkKey(chunkX, chunkZ);
        }

        private int size() {
            return blocks.size() + biomes.size();
        }

        private void put(long key, FabricEdits.NativeCell cell) {
            var map = cell.kind() == EditDelta.Kind.BLOCK ? blocks : biomes;
            var previous = map.put(key, cell);
            // Room for the before-value read at snapshot, as EditDelta accounts it.
            bytes += 128L + 2L * cell.target().estimatedBytes()
                    - (previous == null ? 0 : 128L + 2L * previous.target().estimatedBytes());
        }

        private List<FabricEdits.NativeCell> cells() {
            List<FabricEdits.NativeCell> cells = new ArrayList<>(size());
            cells.addAll(blocks.values());
            cells.addAll(biomes.values());
            return cells;
        }
    }

    private final Host host;
    // Access order: the least recently written chunk is sealed first. Edit thread, holding this.
    private final LinkedHashMap<Long, Buffer> open = new LinkedHashMap<>(OPEN_CHUNKS, 0.75f, true);
    private final Map<Long, Entry> blocks = new ConcurrentHashMap<>();
    private final Map<Long, Entry> biomes = new ConcurrentHashMap<>();
    // Guarded by this: sealed units in order, and the chunks with a unit admitted.
    private final ArrayDeque<Buffer> queue = new ArrayDeque<>();
    private final Set<Long> busy = new HashSet<>();
    private final List<UnsupportedBlockException> skipped = new ArrayList<>();
    private int outstanding;
    private long outstandingBytes;
    private int admitted;
    private boolean retrying;
    private RuntimeException failure;
    private boolean reported;
    private volatile CommitReceipt last;

    public NativeSessionWriter(Host host) {
        this.host = host;
    }

    /** Whether writes with these side effects may share a unit: none has cross-cell callbacks. */
    public static boolean batchable(SideEffectSet effects) {
        return !effects.shouldApply(SideEffect.UPDATE) && !effects.shouldApply(SideEffect.NEIGHBORS)
                && !effects.shouldApply(SideEffect.VALIDATION);
    }

    /**
     * Run on the server thread and wait; the server's task queue runs between ticks, where FAWE's
     * sync queue waits for the end of one. Failures are wrapped as FAWE's sync wraps them.
     */
    public static <T> T onServer(MinecraftServer server, Supplier<T> task) {
        if (server.isSameThread()) {
            return task.get();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        server.execute(() -> {
            // A stopped server runs tasks on the caller, which must never touch the world.
            if (!server.isSameThread()) {
                result.completeExceptionally(new RejectedExecutionException("The server is not running"));
                return;
            }
            try {
                result.complete(task.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        while (true) {
            try {
                return result.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException waiting) {
                if (!server.isRunning()) {
                    throw new RuntimeException(new RejectedExecutionException("The server stopped"));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interrupted);
            } catch (ExecutionException failure) {
                throw new RuntimeException(failure);
            }
        }
    }

    /** Buffer one cell for its chunk's unit; a later write to the same cell replaces it. */
    public synchronized void write(EditDelta.Kind kind, int x, int y, int z, EditValue value, BaseBlock block,
            SideEffectSet effects) {
        if (host.server().isSameThread()) {
            throw new UnsupportedOperationException("Use the asynchronous FabricEdits API on the server thread");
        }
        if (failure != null) {
            reported = true;
            throw failure;
        }
        long chunk = chunkKey(x >> 4, z >> 4);
        Consumer<CommitReceipt> history = host.history();
        Buffer buffer = open.get(chunk);
        // A unit has one set of side effects and one history: a change seals what came before.
        if (buffer != null && (!sameEffects(buffer.effects, effects) || buffer.history != history)) {
            seal(buffer);
            buffer = null;
        }
        if (buffer == null) {
            if (open.size() == OPEN_CHUNKS) {
                seal(open.values().iterator().next());
            }
            buffer = new Buffer(x >> 4, z >> 4, effects, history);
            open.put(chunk, buffer);
        }
        long key = BlockPos.asLong(x, y, z);
        buffer.put(key, new FabricEdits.NativeCell(kind, x, y, z, value));
        (kind == EditDelta.Kind.BLOCK ? blocks : biomes).put(key, new Entry(new Pending(value, block), buffer));
        if (buffer.size() >= UNIT_CELLS || buffer.bytes > UNIT_BYTES) {
            seal(buffer);
        }
    }

    /** This session's unpublished value for the cell, or null; a biome cell is its quart origin. */
    public Pending pending(EditDelta.Kind kind, int x, int y, int z) {
        Entry entry = (kind == EditDelta.Kind.BLOCK ? blocks : biomes).get(BlockPos.asLong(x, y, z));
        return entry == null ? null : entry.pending();
    }

    /** Send every open unit and wait for all of them; the first failure is thrown once. */
    public synchronized void drain() {
        for (Buffer buffer : List.copyOf(open.values())) {
            seal(buffer);
        }
        while (outstanding > 0 && awaitCompletion()) {
            // Woken by each completion.
        }
        if (failure != null && !reported) {
            reported = true;
            throw failure;
        }
    }

    /** Cells skipped for lack of lifecycle support since the last call. */
    public synchronized List<UnsupportedBlockException> takeSkipped() {
        List<UnsupportedBlockException> taken = List.copyOf(skipped);
        skipped.clear();
        return taken;
    }

    /** The latest terminal receipt. */
    public CommitReceipt last() {
        return last;
    }

    // Edit thread, holding this. Waits while too much is outstanding; after a failure it is dropped.
    private void seal(Buffer buffer) {
        open.remove(buffer.chunk());
        while ((outstanding >= OUTSTANDING_UNITS || outstanding > 0 && outstandingBytes + buffer.bytes > OUTSTANDING_BYTES)
                && failure == null && awaitCompletion()) {
            // Woken by each completion.
        }
        if (failure != null) {
            release(buffer);
            return;
        }
        outstanding++;
        outstandingBytes += buffer.bytes;
        queue.addLast(buffer);
        host.server().execute(this::pump);
    }

    // Holding this. False once waiting cannot end: the server stopped with units outstanding.
    private boolean awaitCompletion() {
        try {
            wait(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(new RuntimeException(interrupted));
            return false;
        }
        if (!host.server().isRunning() && outstanding > 0) {
            fail(new RuntimeException(new RejectedExecutionException("The server stopped during this edit")));
            dropQueued();
            return false;
        }
        return true;
    }

    // Server thread: admit queued units in order, skipping a chunk whose earlier unit is in flight.
    private void pump() {
        if (!host.server().isSameThread()) {
            return;
        }
        List<Buffer> ready = new ArrayList<>();
        synchronized (this) {
            if (failure != null) {
                dropQueued();
                return;
            }
            Set<Long> passed = new HashSet<>();
            for (Iterator<Buffer> units = queue.iterator(); units.hasNext() && admitted + ready.size() < ADMITTED_UNITS;) {
                Buffer unit = units.next();
                // A later unit of a passed chunk waits too, so each chunk's units keep their order.
                if (busy.contains(unit.chunk()) || !passed.add(unit.chunk())) {
                    continue;
                }
                units.remove();
                busy.add(unit.chunk());
                ready.add(unit);
            }
            admitted += ready.size();
        }
        for (Buffer unit : ready) {
            submit(unit);
        }
    }

    // Server thread. A full coordinator puts the unit back and asks again next tick.
    private void submit(Buffer buffer) {
        EditActorContext context = null;
        try {
            context = host.unitContext(buffer.effects);
            host.authorize(context.operationId());
            var unit = FabricEdits.nativeService(host.server()).submitNativeChunk(context, buffer.chunkX,
                    buffer.chunkZ, buffer.cells(), buffer.history);
            EditActorContext submitted = context;
            unit.completion().whenComplete((receipts, problem) -> finish(buffer, submitted, unit, receipts, problem));
        } catch (FabricEdits.NativeCapacityException full) {
            host.forget(context.operationId());
            synchronized (this) {
                admitted--;
                busy.remove(buffer.chunk());
                queue.addFirst(buffer);
                if (retrying) return;
                retrying = true;
            }
            CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> host.server().execute(() -> {
                synchronized (this) {
                    retrying = false;
                }
                pump();
            }));
        } catch (Throwable problem) {
            finish(buffer, context, null, null, new RuntimeException(new ExecutionException(problem)));
        }
    }

    private void finish(Buffer buffer, EditActorContext context, FabricEdits.NativeUnit unit,
            List<CommitReceipt> receipts, Throwable problem) {
        if (context != null) {
            host.forget(context.operationId());
        }
        Throwable stop = problem;
        if (receipts != null) {
            for (CommitReceipt receipt : receipts) {
                last = receipt;
                if (stop == null && receipt.outcome() == CommitReceipt.Outcome.CONFLICT && receipt.applied().isEmpty()) {
                    stop = new IllegalStateException("Chunk " + buffer.chunkX + "," + buffer.chunkZ
                            + " kept changing while this edit waited; its cells were left unchanged");
                }
            }
        }
        synchronized (this) {
            if (unit != null) {
                skipped.addAll(unit.skipped());
            }
            if (stop != null) {
                fail(host.stop(stop));
            }
            admitted--;
            busy.remove(buffer.chunk());
            settle(buffer);
        }
        release(buffer);
        pump();
    }

    // Holding this: one sealed unit is done, sent or not.
    private void settle(Buffer buffer) {
        outstanding--;
        outstandingBytes -= buffer.bytes;
        notifyAll();
    }

    // Holding this. Queued units are never sent once the session has failed.
    private void dropQueued() {
        while (!queue.isEmpty()) {
            Buffer buffer = queue.removeFirst();
            settle(buffer);
            release(buffer);
        }
    }

    // Holding this. The first failure stops the session; later ones are its consequences.
    private void fail(RuntimeException stop) {
        if (failure == null) {
            failure = stop;
            host.cancel();
        }
    }

    // Once published or dropped, the world is the truth for these cells, unless a later unit wrote them.
    private void release(Buffer buffer) {
        for (Long key : buffer.blocks.keySet()) {
            blocks.computeIfPresent(key, (ignored, entry) -> entry.owner() == buffer ? null : entry);
        }
        for (Long key : buffer.biomes.keySet()) {
            biomes.computeIfPresent(key, (ignored, entry) -> entry.owner() == buffer ? null : entry);
        }
    }

    private static boolean sameEffects(SideEffectSet left, SideEffectSet right) {
        for (SideEffect effect : SideEffect.values()) {
            if (left.getState(effect) != right.getState(effect)) {
                return false;
            }
        }
        return true;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }
}
