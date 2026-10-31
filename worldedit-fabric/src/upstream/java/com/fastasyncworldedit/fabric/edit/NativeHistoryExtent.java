package com.fastasyncworldedit.fabric.edit;

import com.fastasyncworldedit.core.extent.HistoryExtent;
import com.fastasyncworldedit.core.history.changeset.AbstractChangeSet;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.fabric.internal.NBTConverter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.biome.BiomeTypes;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;

/** Native history consumes actual coordinator receipts, including writes followed by failure. */
public final class NativeHistoryExtent extends HistoryExtent {
    private static final java.util.concurrent.ConcurrentMap<com.sk89q.worldedit.history.changeset.ChangeSet, Origin>
            ORIGINS = new com.google.common.collect.MapMaker().weakKeys().makeMap();
    private static final Origin UNKNOWN = new Origin(null, null);
    private final Runnable detach;

    /** In-memory provenance; missing or mixed historical identities stay explicitly unknown. */
    public record Origin(java.util.UUID operationId, java.util.UUID actorId) { }

    /** Legacy/reloaded history has no identity until a durable provenance format is implemented. */
    public static Origin originOf(com.sk89q.worldedit.history.changeset.ChangeSet history) {
        return ORIGINS.getOrDefault(history, UNKNOWN);
    }

    /** Keep upstream history traversal, but attach recording at the native publication boundary. */
    public NativeHistoryExtent(Extent extent, AbstractChangeSet history, Runnable detach) {
        super(extent, history);
        this.detach = detach;
    }

    @Override
    public <B extends BlockStateHolder<B>> boolean setBlock(int x, int y, int z, B block) throws WorldEditException {
        return getExtent().setBlock(x, y, z, block);
    }

    @Override
    public boolean setBiome(BlockVector3 pos, BiomeType biome) {
        return getExtent().setBiome(pos, biome);
    }

    @Override
    public boolean setBiome(int x, int y, int z, BiomeType biome) {
        return getExtent().setBiome(x, y, z, biome);
    }

    @Override
    public Extent disableHistory() {
        detach.run();
        return getExtent();
    }

    /** Called by shared finalization, never by the speculative extent write. */
    public void record(CommitReceipt receipt) {
        AbstractChangeSet history = getChangeSet();
        synchronized (history) {
            if (!receipt.applied().isEmpty()) {
                var context = receipt.context();
                Origin origin = new Origin(context.sessionId(), context.actorId());
                ORIGINS.compute(history, (key, previous) -> previous == null
                        ? (history.size() == 0 && origin.operationId() != null ? origin : UNKNOWN)
                        : (previous.equals(origin) ? previous : UNKNOWN));
            }
            for (var applied : receipt.applied()) {
                EditDelta delta = applied.delta();
                switch (delta.kind()) {
                    case BLOCK -> history.add(delta.x(), delta.y(), delta.z(),
                            block(delta.before()), block(delta.after()));
                    case BIOME -> history.addBiomeChange(delta.x(), delta.y(), delta.z(),
                            BiomeTypes.get(delta.before().id()), BiomeTypes.get(delta.after().id()));
                    default -> throw new UnsupportedOperationException("No native history codec for " + delta.kind());
                }
            }
        }
    }

    // History gets fresh immutable tags, not the live block entity's mutable data.
    private static BaseBlock block(EditValue value) {
        BlockState state = BlockState.get(value.id());
        return value.nbt() == null ? state.toBaseBlock()
                : state.toBaseBlock(NBTConverter.fromNative(value.nbt().thaw()));
    }
}
