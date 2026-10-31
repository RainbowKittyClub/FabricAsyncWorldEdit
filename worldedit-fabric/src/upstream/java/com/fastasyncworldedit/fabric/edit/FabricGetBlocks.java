package com.fastasyncworldedit.fabric.edit;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;

import com.fastasyncworldedit.core.extent.processor.heightmap.HeightMapType;
import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.fastasyncworldedit.core.queue.CoordinatedChunkGet;
import com.fastasyncworldedit.core.queue.IBlocks;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.queue.IQueueExtent;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.fabric.internal.NBTConverter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.biome.BiomeTypes;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/** Immutable chunk contents and per-reader dependency tracking; no live world references. */
public final class FabricGetBlocks implements CoordinatedChunkGet {
    private final int chunkX;
    private final int chunkZ;
    private final int minY;
    private final int maxY;
    private final long revision;
    private final FabricStateMapping mapping;
    private final char[][] sections;
    private final BiomeType[][] biomes;
    private final byte[][] sky;
    private final byte[][] light;
    private final Map<BlockVector3, FrozenNbt> blockEntities;
    private final Map<HeightMapType, int[]> heights;
    private final BitSet blockReads = new BitSet();
    private final BitSet biomeReads = new BitSet();
    private final Submitter submitter;

    // Only capture constructs backing arrays; no caller gets a reference to them.
    private FabricGetBlocks(LevelChunk chunk, ServerLevel level, FabricStateMapping mapping, Submitter submitter,
            long byteBudget) {
        FabricWorldAccess.refuseAttachments(chunk);
        chunkX = chunk.getPos().x();
        chunkZ = chunk.getPos().z();
        minY = level.getMinY();
        maxY = level.getMaxY();
        this.mapping = mapping;
        this.submitter = submitter;
        revision = ((ChunkRevision) chunk).worldedit$editRevision();
        long retained = level.getSectionsCount() * 20000L + 8192L;
        if (retained > byteBudget) {
            throw new IllegalArgumentException("Snapshot arrays exceed admission budget");
        }
        sections = new char[level.getSectionsCount()][4096];
        biomes = new BiomeType[sections.length][64];
        sky = new byte[sections.length][4096];
        light = new byte[sections.length][4096];
        for (int section = 0; section < sections.length; section++) {
            int baseY = minY + (section << 4);
            for (int index = 0; index < 4096; index++) {
                int x = index & 15;
                int y = baseY + (index >> 8);
                int z = (index >> 4) & 15;
                BlockPos pos = new BlockPos((chunkX << 4) + x, y, (chunkZ << 4) + z);
                sections[section][index] = mapping.ordinal(chunk.getBlockState(pos));
                sky[section][index] = (byte) level.getBrightness(LightLayer.SKY, pos);
                light[section][index] = (byte) level.getBrightness(LightLayer.BLOCK, pos);
            }
            for (int index = 0; index < 64; index++) {
                String id = chunk.getNoiseBiome((chunkX << 2) + (index & 3), (baseY >> 2) + (index >> 4),
                        (chunkZ << 2) + ((index >> 2) & 3)).unwrapKey().orElseThrow().identifier().toString();
                biomes[section][index] = java.util.Objects.requireNonNull(BiomeTypes.get(id), "Unmapped biome " + id);
            }
        }
        Map<BlockVector3, FrozenNbt> tiles = new HashMap<>();
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            var entity = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
            if (entity != null) {
                FabricWorldAccess.refuseAttachments(entity);
                var nbt = entity.saveWithFullMetadata(level.registryAccess());
                if (nbt.sizeInBytes() > 65536) {
                    throw new UnsupportedOperationException("Snapshot block entity exceeds 64 KiB");
                }
                FrozenNbt frozen = FrozenNbt.capture(nbt);
                retained += frozen.estimatedBytes() + 128L;
                if (retained > byteBudget) {
                    throw new IllegalArgumentException("Snapshot NBT exceeds admission budget");
                }
                tiles.put(BlockVector3.at(pos.getX() & 15, pos.getY(), pos.getZ() & 15), frozen);
            }
        }
        blockEntities = Map.copyOf(tiles);
        heights = new EnumMap<>(HeightMapType.class);
        for (HeightMapType type : HeightMapType.values()) {
            Heightmap.Types nativeType = Heightmap.Types.valueOf(type.name());
            int[] values = new int[256];
            for (int index = 0; index < 256; index++) {
                values[index] = chunk.getHeight(nativeType, index & 15, index >> 4);
            }
            heights.put(type, values);
        }
    }

    // Project actual-before facts for completion consumers; only changed tiles appear in history.
    private FabricGetBlocks(FabricGetBlocks source, CommitReceipt receipt) {
        chunkX = source.chunkX;
        chunkZ = source.chunkZ;
        minY = source.minY;
        maxY = source.maxY;
        revision = source.revision;
        mapping = source.mapping;
        submitter = null;
        sections = java.util.Arrays.stream(source.sections).map(char[]::clone).toArray(char[][]::new);
        biomes = java.util.Arrays.stream(source.biomes).map(BiomeType[]::clone).toArray(BiomeType[][]::new);
        sky = source.sky;
        light = source.light;
        heights = source.heights;
        Map<BlockVector3, FrozenNbt> tiles = new HashMap<>();
        for (var applied : receipt.applied()) {
            EditDelta delta = applied.delta();
            if (delta.kind() == EditDelta.Kind.BLOCK) {
                sections[section(delta.y())][index(delta.x(), delta.y(), delta.z())] = mapping.ordinal(delta.before().id());
                if (delta.before().nbt() != null) {
                    tiles.put(BlockVector3.at(delta.x() & 15, delta.y(), delta.z() & 15), delta.before().nbt());
                }
            } else if (delta.kind() == EditDelta.Kind.BIOME) {
                int index = ((delta.y() & 15) >> 2) << 4 | ((delta.z() & 15) >> 2) << 2 | ((delta.x() & 15) >> 2);
                biomes[section(delta.y())][index] = BiomeTypes.get(delta.before().id());
            }
        }
        blockEntities = Map.copyOf(tiles);
    }

    /** Build an isolated actual-before view; speculative tiles cannot enter postprocessing. */
    public FabricGetBlocks committedBefore(CommitReceipt receipt) {
        return new FabricGetBlocks(this, receipt);
    }

    /** Capture loaded state on the owner thread; no public chunk-future/managedBlock call is used. */
    public static FabricGetBlocks capture(ServerLevel level, int chunkX, int chunkZ,
            FabricStateMapping mapping, Submitter submitter) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Snapshot capture requires the server thread");
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            throw new IllegalStateException("Snapshot chunk is not loaded");
        }
        return capture(level, chunkX, chunkZ, mapping, submitter, FabricPreparationService.UNIT_BYTES);
    }

    /** Capture within an already reserved byte budget; reject oversized payloads before handoff. */
    public static FabricGetBlocks capture(ServerLevel level, int chunkX, int chunkZ,
            FabricStateMapping mapping, Submitter submitter, long byteBudget) {
        return capture(level, chunkX, chunkZ, mapping, submitter, byteBudget, null);
    }

    /** Capture sensitive data only after authorizing the explicit current actor on the owner thread. */
    public static FabricGetBlocks capture(ServerLevel level, int chunkX, int chunkZ,
            FabricStateMapping mapping, Submitter submitter, long byteBudget, EditActorContext context) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Snapshot capture requires the server thread");
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            throw new IllegalStateException("Snapshot chunk is not loaded");
        }
        if (!chunk.getBlockEntitiesPos().isEmpty()) {
            com.fastasyncworldedit.fabric.compat.NativeClaims.snapshot(level, context, chunkX, chunkZ);
        }
        return new FabricGetBlocks(chunk, level, mapping, submitter, byteBudget);
    }

    /** Adapter hook carries the explicit operation rather than recovering it from a worker thread. */
    @FunctionalInterface
    public interface Submitter {
        /** Return a registered recursive core future covering actual publication and callbacks. */
        public <T extends Future<T>> T submit(FabricGetBlocks snapshot, IQueueExtent<?> owner,
                IChunk chunk, IChunkSet set, Runnable finalizer);
    }

    /** Count dependencies without allocating per-cell values or records. */
    public synchronized int dependencyCount() {
        return blockReads.cardinality() + biomeReads.cardinality();
    }

    /** Enumerate all observed cells, including NBT and neighbor snapshots supplied by the caller. */
    public synchronized List<PreparedChunkEdit.ReadDependency> dependencies() {
        if (dependencyCount() > 32768) {
            throw new IllegalArgumentException("Read footprint exceeds 32768 cells");
        }
        List<PreparedChunkEdit.ReadDependency> result = new ArrayList<>();
        for (int index = blockReads.nextSetBit(0); index >= 0; index = blockReads.nextSetBit(index + 1)) {
            int local = index & 4095;
            int x = (chunkX << 4) + (local & 15);
            int y = minY + (index >> 8);
            int z = (chunkZ << 4) + ((local >> 4) & 15);
            result.add(new PreparedChunkEdit.ReadDependency(EditDelta.Kind.BLOCK, x, y, z, "",
                    value(local & 15, y, (local >> 4) & 15), revision));
        }
        for (int index = biomeReads.nextSetBit(0); index >= 0; index = biomeReads.nextSetBit(index + 1)) {
            int local = index & 63;
            result.add(new PreparedChunkEdit.ReadDependency(EditDelta.Kind.BIOME,
                    (chunkX << 4) + ((local & 3) << 2), minY + ((index >> 4) << 2),
                    (chunkZ << 4) + (((local >> 2) & 3) << 2), "",
                    new EditValue(biomes[index >> 6][local].id(), null), revision));
        }
        return List.copyOf(result);
    }

    /** Full immutable value without exposing a backing payload. */
    public EditValue value(int x, int y, int z) {
        int section = section(y);
        return new EditValue(mapping.state(sections[section][index(x, y, z)]).getAsString(),
                blockEntities.get(BlockVector3.at(x & 15, y, z & 15)));
    }

    /** Captured authoritative mutation revision, including same-value ABA changes. */
    public long revision() {
        return revision;
    }

    /** Read a before-value for a proposed biome write without contaminating other units' read sets. */
    public BiomeType biomeValue(int x, int y, int z) {
        int index = ((y & 15) >> 2) << 4 | ((z & 15) >> 2) << 2 | ((x & 15) >> 2);
        return biomes[section(y)][index];
    }

    /** Whether handoff must recheck access after other chunks were captured on earlier ticks. */
    public boolean containsSensitiveData() {
        return !blockEntities.isEmpty();
    }

    /** Conservative retained-size estimate for admission accounting. */
    public long estimatedBytes() {
        return sections.length * 20000L + blockEntities.values().stream().mapToLong(FrozenNbt::estimatedBytes).sum()
                + blockEntities.size() * 128L + 8192L;
    }

    /** Captured registry generation, checked again at publication. */
    public long registryGeneration() {
        return mapping.generation();
    }

    @Override
    public <T extends Future<T>> T submitCommit(IQueueExtent<?> owner, IChunk chunk,
            IChunkSet set, Runnable finalizer) {
        if (submitter == null) {
            throw new UnsupportedOperationException("Read-only snapshot has no admitted operation");
        }
        return submitter.submit(this, owner, chunk, set, finalizer);
    }

    @Override
    public <T extends Future<T>> T call(IQueueExtent<? extends IChunk> owner, IChunkSet set, Runnable finalizer) {
        throw new UnsupportedOperationException("Use the coordinated ChunkHolder boundary");
    }

    @Override
    public synchronized char[] load(int layer) {
        int section = layer - getMinSectionPosition();
        if (section < 0 || section >= sections.length) {
            throw new IllegalArgumentException("Section outside snapshot: " + layer);
        }
        blockReads.set(section << 12, (section + 1) << 12);
        return sections[section].clone();
    }

    @Override
    public char[] loadIfPresent(int layer) {
        return hasSection(layer) ? load(layer) : null;
    }

    @Override
    public boolean hasSection(int layer) {
        return layer >= getMinSectionPosition() && layer <= getMaxSectionPosition();
    }

    @Override
    public synchronized BlockState getBlock(int x, int y, int z) {
        int section = section(y);
        int index = index(x, y, z);
        blockReads.set((section << 12) + index);
        return mapping.state(sections[section][index]);
    }

    @Override
    public BaseBlock getFullBlock(int x, int y, int z) {
        BlockState state = getBlock(x, y, z);
        FaweCompoundTag tag = tile(x, y, z);
        return tag == null ? state.toBaseBlock() : state.toBaseBlock(tag.linTag());
    }

    @Override
    public synchronized BiomeType getBiomeType(int x, int y, int z) {
        int section = section(y);
        int index = ((y & 15) >> 2) << 4 | ((z & 15) >> 2) << 2 | ((x & 15) >> 2);
        biomeReads.set((section << 6) + index);
        return biomes[section][index];
    }

    @Override
    public Map<BlockVector3, FaweCompoundTag> tiles() {
        Map<BlockVector3, FaweCompoundTag> result = new HashMap<>();
        blockEntities.forEach((pos, value) -> result.put(pos, tile(pos.x(), pos.y(), pos.z())));
        return Map.copyOf(result);
    }

    @Override
    public FaweCompoundTag tile(int x, int y, int z) {
        getBlock(x, y, z);
        FrozenNbt value = blockEntities.get(BlockVector3.at(x & 15, y, z & 15));
        return value == null ? null : FaweCompoundTag.of(NBTConverter.fromNative(value.thaw()));
    }

    @Override
    public Collection<FaweCompoundTag> entities() {
        throw new UnsupportedOperationException("Detached entity snapshots are not yet supported");
    }

    @Override
    public Set<Entity> getFullEntities() {
        throw new UnsupportedOperationException("Live entity handles are forbidden in detached snapshots");
    }

    @Override
    public FaweCompoundTag entity(UUID uuid) {
        throw new UnsupportedOperationException("Detached entity snapshots are not yet supported");
    }

    @Override
    public int getSkyLight(int x, int y, int z) {
        return sky[section(y)][index(x, y, z)];
    }

    @Override
    public int getEmittedLight(int x, int y, int z) {
        return light[section(y)][index(x, y, z)];
    }

    @Override
    public synchronized int[] getHeightMap(HeightMapType type) {
        blockReads.set(0, sections.length << 12);
        return heights.get(type).clone();
    }

    @Override
    public boolean isCreateCopy() {
        return true;
    }

    @Override
    public int setCreateCopy(boolean createCopy) {
        return 0;
    }

    @Override
    public IChunkGet getCopy(int key) {
        return this;
    }

    @Override
    public int getMinY() {
        return minY;
    }

    @Override
    public int getMaxY() {
        return maxY;
    }

    @Override
    public int getMinSectionPosition() {
        return minY >> 4;
    }

    @Override
    public int getMaxSectionPosition() {
        return maxY >> 4;
    }

    @Override
    public int getSectionCount() {
        return sections.length;
    }

    @Override
    public int getX() {
        return chunkX;
    }

    @Override
    public int getZ() {
        return chunkZ;
    }

    @Override
    public boolean trim(boolean aggressive) {
        return false;
    }

    @Override
    public boolean trim(boolean aggressive, int layer) {
        return false;
    }

    @Override
    public IBlocks reset() {
        throw new UnsupportedOperationException("Snapshots are immutable");
    }

    @Override
    public void removeSectionLighting(int layer, boolean sky) {
        throw new UnsupportedOperationException("Snapshots are immutable");
    }

    @Override
    public void setLightingToGet(char[][] lighting, int start, int end) {
        throw new UnsupportedOperationException("Snapshots are immutable");
    }

    @Override
    public void setSkyLightingToGet(char[][] lighting, int start, int end) {
        throw new UnsupportedOperationException("Snapshots are immutable");
    }

    @Override
    public void setHeightmapToGet(HeightMapType type, int[] data) {
        throw new UnsupportedOperationException("Snapshots are immutable");
    }

    // Reject out-of-height reads instead of accidentally aliasing a section.
    private int section(int y) {
        if (y < minY || y > maxY) {
            throw new IllegalArgumentException("Y outside snapshot: " + y);
        }
        return (y - minY) >> 4;
    }

    // FAWE buffers use YZX indexing, independent of Minecraft palette IDs.
    private static int index(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }
}
