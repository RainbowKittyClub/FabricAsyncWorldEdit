package com.fastasyncworldedit.fabric.edit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.sk89q.worldedit.fabric.internal.NBTConverter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;

/** Freezes a processed core buffer at the coordinated adapter boundary. */
public final class FabricBufferEdits {
    private FabricBufferEdits() { }

    /** Freeze replacement/veto results, including actual read dependencies and independent before-values. */
    public static PreparedChunkEdit freeze(EditActorContext context, FabricGetBlocks snapshot,
            Collection<FabricGetBlocks> readFootprint, IChunkSet processed) {
        List<EditDelta> writes = new ArrayList<>();
        if (processed != null) {
            if (!processed.entities().isEmpty() || !processed.getEntityRemoves().isEmpty()) {
                throw new UnsupportedOperationException("Entity mutation capability is not installed");
            }
            for (int layer = processed.getMinSectionPosition(); layer <= processed.getMaxSectionPosition(); layer++) {
                char[] section = processed.loadIfPresent(layer);
                if (section == null) continue;
                for (int index = 0; index < 4096; index++) {
                    if (section[index] == 0) continue;
                    int x = index & 15;
                    int y = (layer << 4) + (index >> 8);
                    int z = (index >> 4) & 15;
                    var nbt = processed.tile(x, y, z);
                    writes.add(new EditDelta(EditDelta.Kind.BLOCK, (snapshot.getX() << 4) + x, y,
                            (snapshot.getZ() << 4) + z, "", snapshot.value(x, y, z),
                            new EditValue(BlockState.getFromOrdinal(section[index]).getAsString(),
                                    nbt == null ? null : FrozenNbt.capture(NBTConverter.toNative(nbt.linTag())))));
                }
            }
            for (Map.Entry<BlockVector3, FaweCompoundTag> tile : processed.tiles().entrySet()) {
                BlockVector3 position = tile.getKey();
                char[] section = processed.loadIfPresent(position.y() >> 4);
                int index = ((position.y() & 15) << 8) | ((position.z() & 15) << 4) | (position.x() & 15);
                if (section != null && section[index] != 0) continue;
                EditValue before = snapshot.value(position.x(), position.y(), position.z());
                writes.add(new EditDelta(EditDelta.Kind.BLOCK, (snapshot.getX() << 4) + position.x(), position.y(),
                        (snapshot.getZ() << 4) + position.z(), "", before,
                        new EditValue(before.id(), FrozenNbt.capture(NBTConverter.toNative(tile.getValue().linTag())))));
            }
            if (processed.getBiomes() != null) {
                for (int layer = processed.getMinSectionPosition(); layer <= processed.getMaxSectionPosition(); layer++) {
                    if (!processed.hasBiomes(layer)) continue;
                    var biomes = processed.getBiomes()[layer - processed.getMinSectionPosition()];
                    for (int index = 0; index < 64; index++) {
                        if (biomes[index] == null) continue;
                        int x = (index & 3) << 2;
                        int y = (layer << 4) + ((index >> 4) << 2);
                        int z = ((index >> 2) & 3) << 2;
                        writes.add(new EditDelta(EditDelta.Kind.BIOME, (snapshot.getX() << 4) + x, y,
                                (snapshot.getZ() << 4) + z, "", new EditValue(snapshot.biomeValue(x, y, z).id(), null),
                                new EditValue(biomes[index].id(), null)));
                    }
                }
            }
        }
        List<PreparedChunkEdit.ReadDependency> reads = new ArrayList<>(readFootprint.stream()
                .flatMap(value -> value.dependencies().stream()).toList());
        for (EditDelta delta : writes) {
            reads.add(new PreparedChunkEdit.ReadDependency(delta.kind(), delta.x(), delta.y(), delta.z(),
                    delta.key(), delta.before(), snapshot.revision()));
        }
        return new PreparedChunkEdit(context, snapshot.getX(), snapshot.getZ(), snapshot.registryGeneration(), reads, writes);
    }
}
