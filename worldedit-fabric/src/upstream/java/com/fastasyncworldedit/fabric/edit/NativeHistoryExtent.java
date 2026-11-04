package com.fastasyncworldedit.fabric.edit;

import java.util.UUID;

import com.fastasyncworldedit.core.extent.HistoryExtent;
import com.fastasyncworldedit.core.history.changeset.AbstractChangeSet;
import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.fabric.internal.NBTConverter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.biome.BiomeTypes;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;

/** Native history consumes actual coordinator receipts, including writes followed by failure. */
public final class NativeHistoryExtent extends HistoryExtent {
    private final Runnable detach;
    // The bound session, which is also what its receipts name; null without a bound actor.
    private final HistoryProvenance.Origin session;

    /** In-memory or durable provenance; missing or mixed historical identities stay unknown. */
    public static HistoryProvenance.Origin originOf(com.sk89q.worldedit.history.changeset.ChangeSet history) {
        return HistoryProvenance.originOf(history);
    }

    /** Keep upstream history traversal, but attach recording at the native publication boundary. */
    public NativeHistoryExtent(Extent extent, AbstractChangeSet history, HistoryProvenance.Origin session,
            Runnable detach) {
        super(extent, history);
        this.session = session;
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

    // History names the entity actually created: its own UUID and position, not the requested
    // clipboard state, which carries the source entity's UUID and would undo the wrong entity.
    @Override
    public Entity createEntity(Location location, BaseEntity state) {
        return recordCreated(getExtent().createEntity(location, state));
    }

    @Override
    public Entity createEntity(Location location, BaseEntity state, UUID uuid) {
        return recordCreated(getExtent().createEntity(location, state, uuid));
    }

    private Entity recordCreated(Entity created) {
        if (created != null) {
            BaseEntity actual = com.fastasyncworldedit.core.util.TaskManager.taskManager().sync(created::getState);
            if (actual == null || actual.getNbt() == null) {
                throw new IllegalStateException("Created entity has no readable state for history");
            }
            AbstractChangeSet history = getChangeSet();
            synchronized (history) {
                if (session != null) {
                    HistoryProvenance.note(history, session, history.longSize() == 0);
                }
                history.addEntityCreate(FaweCompoundTag.of(actual.getNbt()));
            }
        }
        return created;
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
                HistoryProvenance.note(history, new HistoryProvenance.Origin(context.sessionId(), context.actorId()),
                        history.longSize() == 0);
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
