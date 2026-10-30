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
import org.enginehub.linbus.tree.LinByteArrayTag;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinIntArrayTag;
import org.enginehub.linbus.tree.LinListTag;
import org.enginehub.linbus.tree.LinLongArrayTag;
import org.enginehub.linbus.tree.LinStringTag;
import org.enginehub.linbus.tree.LinTag;

/** Freezes a processed core buffer at the coordinated adapter boundary. */
public final class FabricBufferEdits {
    private FabricBufferEdits() { }

    /** Freeze replacement/veto results, including actual read dependencies and independent before-values. */
    public static PreparedChunkEdit freeze(EditActorContext context, FabricGetBlocks snapshot,
            Collection<FabricGetBlocks> readFootprint, IChunkSet processed) {
        int readCount = readFootprint.stream().mapToInt(FabricGetBlocks::dependencyCount).sum();
        if (readCount > 32768) {
            throw new IllegalArgumentException("Read footprint exceeds 32768 cells before conversion");
        }
        List<EditDelta> writes = new ArrayList<>() {
            private long retained;
            @Override
            public boolean add(EditDelta delta) {
                if (size() >= 4096 || (retained += delta.estimatedBytes()) > (8L << 20)) {
                    throw new IllegalArgumentException("Proposal exceeds bounded conversion budget");
                }
                return super.add(delta);
            }
        };
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
                                    freezeNbt(nbt))));
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
                        new EditValue(before.id(), freezeNbt(tile.getValue()))));
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

    // Refuse before allocating native arrays or the canonical string from a producer's payload.
    private static FrozenNbt freezeNbt(FaweCompoundTag value) {
        if (value == null) return null;
        LinCompoundTag tag = value.linTag();
        remainingNbtBudget(tag, 65536, 0);
        return FrozenNbt.capture(NBTConverter.toNative(tag));
    }

    // Array views are read-only and avoid copying a large array merely to learn its length.
    private static long remainingNbtBudget(LinTag<?> tag, long remaining, int depth) {
        remaining -= 64;
        if (depth > 64 || remaining < 0) throw new IllegalArgumentException("NBT conversion budget exceeded");
        if (tag instanceof LinByteArrayTag array) remaining -= array.view().remaining();
        else if (tag instanceof LinIntArrayTag array) remaining -= 4L * array.view().remaining();
        else if (tag instanceof LinLongArrayTag array) remaining -= 8L * array.view().remaining();
        else if (tag instanceof LinStringTag text) remaining -= 2L * text.value().length();
        else if (tag instanceof LinCompoundTag compound) {
            for (var entry : compound.value().entrySet()) {
                remaining = remainingNbtBudget(entry.getValue(), remaining - 2L * entry.getKey().length(), depth + 1);
            }
        } else if (tag instanceof LinListTag<?> list) {
            for (var child : list.value()) remaining = remainingNbtBudget(child, remaining, depth + 1);
        }
        if (remaining < 0) throw new IllegalArgumentException("NBT conversion budget exceeded");
        return remaining;
    }
}
