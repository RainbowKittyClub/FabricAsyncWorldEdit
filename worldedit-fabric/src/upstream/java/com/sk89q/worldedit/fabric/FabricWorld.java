/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.fabric;

import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.fabric.compat.NativeClaims;
import com.fastasyncworldedit.fabric.edit.CommitReceipt;
import com.fastasyncworldedit.fabric.edit.FabricEdits;
import com.fastasyncworldedit.fabric.edit.EditActorContext;
import com.fastasyncworldedit.fabric.edit.EditDelta;
import com.fastasyncworldedit.fabric.edit.EditValue;
import com.fastasyncworldedit.fabric.edit.FrozenNbt;
import com.fastasyncworldedit.fabric.edit.NativeHistoryExtent;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.implementation.packet.ChunkPacket;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.util.concurrent.Futures;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseItem;
import com.sk89q.worldedit.blocks.BaseItemStack;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.fabric.internal.ExtendedMinecraftServer;
import com.sk89q.worldedit.fabric.internal.FabricEntity;
import com.sk89q.worldedit.fabric.internal.FabricServerLevelDelegateProxy;
import com.sk89q.worldedit.fabric.internal.FabricWorldNativeAccess;
import com.sk89q.worldedit.fabric.internal.NBTConverter;
import com.sk89q.worldedit.function.mask.AbstractExtentMask;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.internal.Constants;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.util.TreeGenerator.TreeType;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import com.sk89q.worldedit.util.io.file.SafeFiles;
import com.sk89q.worldedit.world.AbstractWorld;
import com.sk89q.worldedit.world.RegenOptions;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.generation.ConfiguredFeatureType;
import com.sk89q.worldedit.world.generation.StructureType;
import com.sk89q.worldedit.world.item.ItemTypes;
import com.sk89q.worldedit.world.weather.WeatherType;
import com.sk89q.worldedit.world.weather.WeatherTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.EndFeatures;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import org.enginehub.linbus.tree.LinCompoundTag;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * An adapter to Minecraft worlds for WorldEdit.
 */
public class FabricWorld extends AbstractWorld implements com.sk89q.worldedit.world.ActorBoundWorld {

    private static final RandomSource random = RandomSource.create();

    private static Identifier getDimensionRegistryKey(Level world) {
        return Objects.requireNonNull(world.getServer(), "server cannot be null")
            .registryAccess()
            .lookupOrThrow(Registries.DIMENSION_TYPE)
            .getKey(world.dimensionType());
    }

    private final WeakReference<Level> worldRef;
    private final String worldNameRef;
    private final FabricWorldNativeAccess worldNativeAccess;
    private final EditActorContext editContext;
    private final com.sk89q.worldedit.extension.platform.Actor actor;
    // Cells refused for lack of lifecycle support; the session leaves them unchanged and reports on flush.
    private final List<com.fastasyncworldedit.fabric.edit.UnsupportedBlockException> skipped =
            Collections.synchronizedList(new ArrayList<>());
    private java.util.function.Consumer<CommitReceipt> nativeHistory = receipt -> { };
    private volatile CommitReceipt lastNativeReceipt;

    /**
     * Construct a new world.
     *
     * @param world the world
     */
    FabricWorld(Level world) {
        this(world, null, null);
    }

    private FabricWorld(Level world, EditActorContext context, com.sk89q.worldedit.extension.platform.Actor actor) {
        checkNotNull(world);
        this.editContext = context;
        this.actor = actor;
        this.worldRef = new WeakReference<>(world);
        LevelData levelData = world.getLevelData();
        this.worldNameRef = levelData instanceof ServerLevelData serverLevelData
            ? serverLevelData.getLevelName()
            : "world";
        this.worldNativeAccess = new FabricWorldNativeAccess(worldRef);
    }

    /**
     * Get the underlying handle to the world.
     *
     * @return the world
     * @throws RuntimeException thrown if a reference to the world was lost (i.e. world was unloaded)
     */
    public Level getWorld() {
        Level world = worldRef.get();
        if (world != null) {
            return world;
        } else {
            throw new RuntimeException("The reference to the world was lost (i.e. the world may have been unloaded)");
        }
    }

    @Override
    public FabricWorld bindActor(com.sk89q.worldedit.extension.platform.Actor actor) {
        EditActorContext context = actor == null ? null : new EditActorContext(actor.getUniqueId(),
                actor.getName(), EditActorContext.Source.API, java.util.UUID.randomUUID(), null, null,
                getWorld().dimension().identifier().toString(), SideEffectSet.defaults(),
                new EditActorContext.Cancellation());
        return new FabricWorld(getWorld(), context, actor);
    }

    @Override
    public FabricWorld bindReplay(com.sk89q.worldedit.extension.platform.Actor actor,
            com.sk89q.worldedit.history.changeset.ChangeSet history, boolean redo) {
        var origin = NativeHistoryExtent.originOf(history);
        EditActorContext context = actor == null ? null : new EditActorContext(actor.getUniqueId(),
                actor.getName(), redo ? EditActorContext.Source.REDO : EditActorContext.Source.UNDO,
                java.util.UUID.randomUUID(), origin.operationId(), origin.actorId(),
                getWorld().dimension().identifier().toString(), SideEffectSet.defaults(),
                new EditActorContext.Cancellation());
        return new FabricWorld(getWorld(), context, actor);
    }

    @Override
    public Extent withCommittedHistory(Extent extent,
            com.fastasyncworldedit.core.history.changeset.AbstractChangeSet history) {
        var session = editContext == null ? null
                : new com.fastasyncworldedit.fabric.edit.HistoryProvenance.Origin(editContext.operationId(), editContext.actorId());
        NativeHistoryExtent committed = new NativeHistoryExtent(extent, history, session,
                () -> nativeHistory = receipt -> { });
        nativeHistory = committed::record;
        return committed;
    }

    @Override
    public void checkOperation(String operation) {
        NativeClaims.unsupported(operation);
    }

    @Override
    public String getName() {
        LevelData levelProperties = getWorld().getLevelData();
        return ((ServerLevelData) levelProperties).getLevelName();
    }

    @Override
    public String getNameUnsafe() {
        return worldNameRef;
    }

    @Override
    public String id() {
        return getName() + "_" + getDimensionRegistryKey(getWorld());
    }

    @Override
    public Path getStoragePath() {
        final Level world = getWorld();
        MinecraftServer server = world.getServer();
        checkState(server instanceof ExtendedMinecraftServer, "Need a server world");
        return ((ExtendedMinecraftServer) server).getStoragePath(world);
    }

    @Override
    public <B extends BlockStateHolder<B>> boolean setBlock(BlockVector3 position, B block, SideEffectSet sideEffects) throws WorldEditException {
        return nativeWrite(EditDelta.Kind.BLOCK, position,
                sideEffects.with(SideEffect.VALIDATION, SideEffect.State.OFF), () -> {
                    BaseBlock target = block.toBaseBlock();
                    if (sideEffects.shouldApply(SideEffect.VALIDATION)) {
                        BlockState validated = FabricAdapter.adapt(worldNativeAccess.getValidBlockForPosition(
                                FabricAdapter.adapt(target.toImmutableState()), FabricAdapter.toBlockPos(position)));
                        target = validated.toBaseBlock(target.getNbtReference());
                    }
                    return new EditValue(target.toImmutableState().getAsString(), target.getNbt() == null
                            ? null : FrozenNbt.capture(NBTConverter.toNative(target.getNbt())));
                });
    }

    @Override
    public Set<SideEffect> applySideEffects(BlockVector3 position, BlockState previousType, SideEffectSet sideEffectSet) {
        worldNativeAccess.applySideEffects(position, previousType, sideEffectSet);
        return Sets.intersection(FabricWorldEdit.inst.getPlatform().getSupportedSideEffects(), sideEffectSet.getSideEffectsToApply());
    }

    @Override
    public int getBlockLightLevel(BlockVector3 position) {
        checkNotNull(position);
        return getWorld().getMaxLocalRawBrightness(FabricAdapter.toBlockPos(position));
    }

    @Override
    public boolean clearContainerBlockContents(BlockVector3 position) {
        checkNotNull(position);

        return nativeWrite(EditDelta.Kind.BLOCK, position, SideEffectSet.none(), () -> {
            BlockPos pos = FabricAdapter.toBlockPos(position);
            NativeClaims.snapshot((ServerLevel) getWorld(), editContext, pos.getX() >> 4, pos.getZ() >> 4);
            EditValue before = nativeValue(pos);
            if (before.nbt() == null) {
                return before;
            }
            String id = FabricAdapter.adapt(getWorld().getBlockState(pos)).getBlockType().id();
            if (!Set.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel",
                    "minecraft:furnace").contains(id)) {
                throw new UnsupportedOperationException("No verified native inventory codec for " + id);
            }
            var cleared = before.nbt().thaw();
            cleared.remove("Items");
            return new EditValue(before.id(), FrozenNbt.capture(cleared));
        });
    }

    @Override
    public BiomeType getBiome(BlockVector3 position) {
        checkNotNull(position);
        ChunkAccess chunk = getWorld().getChunk(position.x() >> 4, position.z() >> 4);
        return getBiomeInChunk(position, chunk);
    }

    private BiomeType getBiomeInChunk(BlockVector3 position, ChunkAccess chunk) {
        return FabricAdapter.adapt(
            chunk.getNoiseBiome(position.x() >> 2, position.y() >> 2, position.z() >> 2).value()
        );
    }

    @Override
    public boolean setBiome(BlockVector3 position, BiomeType biome) {
        checkNotNull(position);
        return setBiome(position.x(), position.y(), position.z(), biome);
    }

    @Override
    public boolean setBiome(int x, int y, int z, BiomeType biome) {
        checkNotNull(biome);
        return nativeWrite(EditDelta.Kind.BIOME, BlockVector3.at(x & ~3, y & ~3, z & ~3),
                SideEffectSet.none(), () -> new EditValue(biome.id(), null));
    }

    private static final LoadingCache<ServerLevel, FabricFakePlayer> fakePlayers
            = CacheBuilder.newBuilder().weakKeys().softValues().build(CacheLoader.from(FabricFakePlayer::new));

    @Override
    public boolean useItem(BlockVector3 position, BaseItem item, Direction face) {
        NativeClaims.unsupported("item callbacks");
        ItemStack stack = FabricAdapter.adapt(new BaseItemStack(item.getType(), item.getNbtReference(), 1));
        ServerLevel world = (ServerLevel) getWorld();
        final FabricFakePlayer fakePlayer;
        try {
            fakePlayer = fakePlayers.get(world);
        } catch (ExecutionException ignored) {
            return false;
        }
        fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, stack);
        fakePlayer.absSnapTo(position.x(), position.y(), position.z(),
                (float) face.toVector().toYaw(), (float) face.toVector().toPitch());
        final BlockPos blockPos = FabricAdapter.toBlockPos(position);
        final BlockHitResult rayTraceResult = new BlockHitResult(FabricAdapter.toVec3(position),
                FabricAdapter.adapt(face), blockPos, false);
        UseOnContext itemUseContext = new UseOnContext(fakePlayer, InteractionHand.MAIN_HAND, rayTraceResult);
        InteractionResult used = stack.useOn(itemUseContext);
        if (used != InteractionResult.SUCCESS) {
            // try activating the block
            used = getWorld().getBlockState(blockPos).useItemOn(stack, world, fakePlayer, InteractionHand.MAIN_HAND, rayTraceResult);
        }
        if (used != InteractionResult.SUCCESS) {
            used = stack.use(world, fakePlayer, InteractionHand.MAIN_HAND);
        }
        return used == InteractionResult.SUCCESS;
    }

    @Override
    public void dropItem(Vector3 position, BaseItemStack item) {
        NativeClaims.unsupported("item entities");
        checkNotNull(position);
        checkNotNull(item);

        if (item.getType() == ItemTypes.AIR) {
            return;
        }

        ItemEntity entity = new ItemEntity(getWorld(), position.x(), position.y(), position.z(), FabricAdapter.adapt(item));
        entity.setPickUpDelay(10);
        getWorld().addFreshEntity(entity);
    }

    @Override
    public void simulateBlockMine(BlockVector3 position) {
        NativeClaims.unsupported("simulated mining");
        BlockPos pos = FabricAdapter.toBlockPos(position);
        getWorld().destroyBlock(pos, true);
    }

    @Override
    public boolean canPlaceAt(BlockVector3 position, BlockState blockState) {
        return FabricAdapter.adapt(blockState).canSurvive(getWorld(), FabricAdapter.toBlockPos(position));
    }

    @Override
    public boolean regenerate(Region region, Extent extent, RegenOptions options) {
        NativeClaims.unsupported("regeneration");
        // Don't even try to regen if it's going to fail.
        ChunkSource provider = getWorld().getChunkSource();
        if (!(provider instanceof ServerChunkCache)) {
            return false;
        }

        if (options.getSeed().isPresent()) {
            throw new UnsupportedOperationException("26.3 worldgen does not support overriding the seed for regen");
        }
        try {
            doRegen(region, extent, options);
        } catch (Exception e) {
            throw new IllegalStateException("Regen failed", e);
        }

        return true;
    }

    private void doRegen(Region region, Extent extent, RegenOptions options) throws Exception {
        Path tempDir = Files.createTempDirectory("WorldEditWorldGen");
        LevelStorageSource levelStorage = LevelStorageSource.createDefault(tempDir);
        try (LevelStorageSource.LevelStorageAccess session = levelStorage.createAccess("WorldEditTempGen")) {
            ServerLevel originalWorld = (ServerLevel) getWorld();
            ResourceKey<Level> worldRegKey = originalWorld.dimension();
            try (ServerLevel serverWorld = new ServerLevel(
                originalWorld.getServer(), Util.backgroundExecutor(), session,
                ((ServerLevelData) originalWorld.getLevelData()),
                worldRegKey,
                new LevelStem(
                    originalWorld.dimensionTypeRegistration(),
                    originalWorld.getChunkSource().getGenerator()
                ),
                originalWorld.isDebug(),
                originalWorld.getSeed(),
                // No spawners are needed for this world.
                ImmutableList.of(),
                // This controls ticking, we don't need it so set it to false.
                false
            )) {
                regenForWorld(region, extent, serverWorld, options);

                // drive the server executor until all tasks are popped off
                originalWorld.getServer().managedBlock(() -> originalWorld.getServer().getPendingTasksCount() == 0);
            }
        } finally {
            SafeFiles.tryHardToDeleteDir(tempDir);
        }
    }

    private void regenForWorld(Region region, Extent extent, ServerLevel serverWorld,
                               RegenOptions options) throws WorldEditException {
        List<CompletableFuture<ChunkAccess>> chunkLoadings = submitChunkLoadTasks(region, serverWorld);

        // drive executor until loading finishes
        serverWorld.getChunkSource().mainThreadProcessor
            .managedBlock(() -> {
                // bail out early if a future fails
                if (chunkLoadings.stream().anyMatch(ftr ->
                    ftr.isDone() && Futures.getUnchecked(ftr) == null
                )) {
                    return false;
                }
                return chunkLoadings.stream().allMatch(CompletableFuture::isDone);
            });

        Map<ChunkPos, ChunkAccess> chunks = new HashMap<>();
        for (CompletableFuture<ChunkAccess> future : chunkLoadings) {
            @Nullable
            ChunkAccess chunk = future.getNow(null);
            checkState(chunk != null, "Failed to generate a chunk, regen failed.");
            chunks.put(chunk.getPos(), chunk);
        }

        for (BlockVector3 vec : region) {
            BlockPos pos = FabricAdapter.toBlockPos(vec);
            ChunkAccess chunk = chunks.get(ChunkPos.containing(pos));
            BlockStateHolder<?> state = FabricAdapter.adapt(chunk.getBlockState(pos));
            BlockEntity blockEntity = chunk.getBlockEntity(pos);
            if (blockEntity != null) {
                var tagValueOutput = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, getWorld().registryAccess());
                blockEntity.saveWithId(tagValueOutput);
                net.minecraft.nbt.CompoundTag tag = tagValueOutput.buildResult();
                state = state.toBaseBlock(LazyReference.from(() -> NBTConverter.fromNative(tag)));
            }
            extent.setBlock(vec, state.toBaseBlock());

            if (options.shouldRegenBiomes()) {
                BiomeType biome = getBiomeInChunk(vec, chunk);
                extent.setBiome(vec, biome);
            }
        }
    }

    private List<CompletableFuture<ChunkAccess>> submitChunkLoadTasks(Region region, ServerLevel world) {
        List<CompletableFuture<ChunkAccess>> chunkLoadings = new ArrayList<>();
        // Pre-gen all the chunks
        for (BlockVector2 chunk : region.getChunks()) {
            chunkLoadings.add(
                world.getChunkSource().getChunkFuture(chunk.x(), chunk.z(), ChunkStatus.FEATURES, true)
                    .thenApply(either -> either.orElse(null))
            );
        }
        return chunkLoadings;
    }

    @Nullable
    private static ResourceKey<Feature> createTreeFeatureGenerator(TreeType type) {
        return switch (type) {
            // Based off of the SaplingGenerator class, as well as uses of DefaultBiomeFeatures fields
            case TREE -> TreeFeatures.OAK;
            case BIG_TREE -> TreeFeatures.FANCY_OAK;
            case REDWOOD -> TreeFeatures.SPRUCE;
            case TALL_REDWOOD -> TreeFeatures.MEGA_SPRUCE;
            case MEGA_REDWOOD -> TreeFeatures.MEGA_PINE;
            case BIRCH -> TreeFeatures.BIRCH;
            case JUNGLE -> TreeFeatures.MEGA_JUNGLE_TREE;
            case SMALL_JUNGLE -> TreeFeatures.JUNGLE_TREE;
            case SHORT_JUNGLE -> TreeFeatures.JUNGLE_TREE_NO_VINE;
            case JUNGLE_BUSH -> TreeFeatures.JUNGLE_BUSH;
            case SWAMP -> TreeFeatures.SWAMP_OAK;
            case ACACIA -> TreeFeatures.ACACIA;
            case DARK_OAK -> TreeFeatures.DARK_OAK;
            case TALL_BIRCH -> TreeFeatures.SUPER_BIRCH_BEES_0002;
            case RED_MUSHROOM -> TreeFeatures.HUGE_RED_MUSHROOM;
            case BROWN_MUSHROOM -> TreeFeatures.HUGE_BROWN_MUSHROOM;
            case WARPED_FUNGUS -> TreeFeatures.WARPED_FUNGUS;
            case CRIMSON_FUNGUS -> TreeFeatures.CRIMSON_FUNGUS;
            case CHORUS_PLANT -> EndFeatures.CHORUS_PLANT;
            case MANGROVE -> TreeFeatures.MANGROVE;
            case TALL_MANGROVE -> TreeFeatures.TALL_MANGROVE;
            case CHERRY -> TreeFeatures.CHERRY;
            case PALE_OAK -> TreeFeatures.PALE_OAK;
            case PALE_OAK_CREAKING -> TreeFeatures.PALE_OAK_CREAKING;
            case RANDOM -> createTreeFeatureGenerator(TreeType.values()[ThreadLocalRandom.current().nextInt(TreeType.values().length)]);
            default -> null;
        };
    }

    @Override
    public boolean generateTree(TreeType type, EditSession editSession, BlockVector3 position) throws MaxChangedBlocksException {
        ServerLevel world = (ServerLevel) getWorld();
        Feature generator = Optional.ofNullable(createTreeFeatureGenerator(type))
            .map(k -> world.registryAccess().lookupOrThrow(Registries.FEATURE).getValue(k))
            .orElse(null);
        ServerChunkCache chunkManager = world.getChunkSource();
        if (type == TreeType.CHORUS_PLANT) {
            position = position.add(0, 1, 0);
        }
        try (FabricServerLevelDelegateProxy.LevelAndProxy proxyLevel = FabricServerLevelDelegateProxy.newInstance(editSession, world)) {
            return generator != null && generator.place(
                proxyLevel.level(), chunkManager.getGenerator(), random,
                FabricAdapter.toBlockPos(position)
            );
        }
    }

    @Override
    public boolean generateTree(com.sk89q.worldedit.world.generation.TreeType type,
            EditSession editSession, BlockVector3 position) throws MaxChangedBlocksException {
        ServerLevel world = (ServerLevel) getWorld();
        PlacedFeature generator = world.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE)
            .getValue(Identifier.parse(type.id()));
        try (FabricServerLevelDelegateProxy.LevelAndProxy proxyLevel = FabricServerLevelDelegateProxy.newInstance(editSession, world)) {
            return generator != null && generator.place(proxyLevel.level(),
                world.getChunkSource().getGenerator(), random, FabricAdapter.toBlockPos(position));
        }
    }

    public boolean generateFeature(ConfiguredFeatureType type, EditSession editSession, BlockVector3 position) {
        ServerLevel world = (ServerLevel) getWorld();
        Feature feature = world.registryAccess().lookupOrThrow(Registries.FEATURE).getValue(Identifier.tryParse(type.id()));
        ServerChunkCache chunkManager = world.getChunkSource();
        try (FabricServerLevelDelegateProxy.LevelAndProxy proxyLevel = FabricServerLevelDelegateProxy.newInstance(editSession, world)) {
            return feature != null && feature.place(
                proxyLevel.level(), chunkManager.getGenerator(), random,
                FabricAdapter.toBlockPos(position)
            );
        } catch (MaxChangedBlocksException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean generateStructure(StructureType type, EditSession editSession, BlockVector3 position) {
        ServerLevel world = (ServerLevel) getWorld();
        Registry<Structure> structureRegistry = world.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Structure structure = structureRegistry.getValue(Identifier.tryParse(type.id()));
        if (structure == null) {
            return false;
        }

        ServerChunkCache chunkManager = world.getChunkSource();
        try (FabricServerLevelDelegateProxy.LevelAndProxy proxyLevel = FabricServerLevelDelegateProxy.newInstance(editSession, world)) {
            ChunkPos chunkPos = ChunkPos.containing(new BlockPos(position.x(), position.y(), position.z()));
            StructureStart structureStart = structure.generate(
                structureRegistry.wrapAsHolder(structure), world.dimension(), world.registryAccess(),
                chunkManager.getGenerator(), chunkManager.getGenerator().getBiomeSource(),
                chunkManager.randomState().createClimateSampler(SamplerContext.builder().enableCaches().build()),
                chunkManager.randomState(),
                world.getStructureTemplateManager(), world.getSeed(), chunkPos, 0, proxyLevel.level(),
                biome -> true
            );

            if (!structureStart.isValid()) {
                return false;
            } else {
                BoundingBox boundingBox = structureStart.getBoundingBox();
                ChunkPos min = new ChunkPos(SectionPos.blockToSectionCoord(boundingBox.minX()), SectionPos.blockToSectionCoord(boundingBox.minZ()));
                ChunkPos max = new ChunkPos(SectionPos.blockToSectionCoord(boundingBox.maxX()), SectionPos.blockToSectionCoord(boundingBox.maxZ()));
                ChunkPos.rangeClosed(min, max).forEach((chunkPosx) ->
                    structureStart.placeInChunk(
                        proxyLevel.level(), world.structureManager(), chunkManager.getGenerator(), world.getRandom(),
                        new BoundingBox(chunkPosx.getMinBlockX(), world.getMinY(), chunkPosx.getMinBlockZ(),
                            chunkPosx.getMaxBlockX(), world.getMaxY(), chunkPosx.getMaxBlockZ()),
                        chunkPosx
                    )
                );
                return true;
            }
        } catch (MaxChangedBlocksException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void checkLoadedChunk(BlockVector3 pt) {
        getWorld().getChunk(FabricAdapter.toBlockPos(pt));
    }

    @Override
    public void fixAfterFastMode(Iterable<BlockVector2> chunks) {
        fixLighting(chunks);
    }

    public void sendBiomeUpdates(Iterable<BlockVector2> chunks) {
        List<ChunkAccess> nativeChunks = chunks instanceof Collection<BlockVector2> chunkCollection ? Lists.newArrayListWithCapacity(chunkCollection.size()) : Lists.newArrayList();
        for (BlockVector2 chunk : chunks) {
            nativeChunks.add(getWorld().getChunk(chunk.x(), chunk.z(), ChunkStatus.BIOMES, false));
        }
        ((ServerLevel) getWorld()).getChunkSource().chunkMap.resendBiomesForChunks(nativeChunks);
    }

    @Override
    public void fixLighting(Iterable<BlockVector2> chunks) {
        Level world = getWorld();
        for (BlockVector2 chunk : chunks) {
            world.getChunkSource().getLightEngine().setLightEnabled(
                new ChunkPos(chunk.x(), chunk.z()), true
            );
        }
    }

    @Override
    public boolean playEffect(Vector3 position, int type, int data) {
        // TODO update sound API
        // getWorld().playSound(type, FabricAdapter.toBlockPos(position.toBlockPoint()), data);
        return true;
    }

    @Override
    public WeatherType getWeather() {
        WeatherData info = getWorld().getServer().getWeatherData();
        if (info.isThundering()) {
            return WeatherTypes.THUNDER_STORM;
        }
        if (info.isRaining()) {
            return WeatherTypes.RAIN;
        }
        return WeatherTypes.CLEAR;
    }

    @Override
    public long getRemainingWeatherDuration() {
        WeatherData info = getWorld().getServer().getWeatherData();
        if (info.isThundering()) {
            return info.getThunderTime();
        }
        if (info.isRaining()) {
            return info.getRainTime();
        }
        return info.getClearWeatherTime();
    }

    @Override
    public void setWeather(WeatherType weatherType) {
        setWeather(weatherType, 0);
    }

    @Override
    public void setWeather(WeatherType weatherType, long duration) {
        WeatherData info = getWorld().getServer().getWeatherData();
        if (weatherType == WeatherTypes.THUNDER_STORM) {
            info.setClearWeatherTime(0);
            info.setThundering(true);
            info.setThunderTime((int) duration);
        } else if (weatherType == WeatherTypes.RAIN) {
            info.setClearWeatherTime(0);
            info.setRaining(true);
            info.setRainTime((int) duration);
        } else if (weatherType == WeatherTypes.CLEAR) {
            info.setRaining(false);
            info.setThundering(false);
            info.setClearWeatherTime((int) duration);
        }
    }

    @Override
    public int getMinY() {
        return getWorld().getMinY();
    }

    @Override
    public int getMaxY() {
        return getWorld().getMaxY();
    }

    @Override
    public BlockVector3 getSpawnPosition() {
        return FabricAdapter.adapt(getWorld().getLevelData().getRespawnData().pos());
    }

    @Override
    public BlockState getBlock(BlockVector3 position) {
        net.minecraft.world.level.block.state.BlockState mcState = getWorld()
                .getChunk(position.x() >> 4, position.z() >> 4)
                .getBlockState(FabricAdapter.toBlockPos(position));

        return FabricAdapter.adapt(mcState);
    }

    @Override
    public BaseBlock getFullBlock(BlockVector3 position) {
        return TaskManager.taskManager().sync(() -> {
            BlockPos pos = FabricAdapter.toBlockPos(position);
            if (getWorld().getBlockEntity(pos) != null) {
                NativeClaims.snapshot((ServerLevel) getWorld(), editContext, position.x() >> 4, position.z() >> 4);
            }
            EditValue value = nativeValue(pos);
            BlockState state = BlockState.get(value.id());
            return value.nbt() == null ? state.toBaseBlock()
                    : state.toBaseBlock(NBTConverter.fromNative(value.nbt().thaw()));
        });
    }

    /** Last immutable native result; a failed finalization is also retained here. */
    public CommitReceipt lastNativeReceipt() {
        return lastNativeReceipt;
    }

    // Synchronous WNA callers may wait only off-thread; owner callers must use the asynchronous API.
    private boolean nativeWrite(EditDelta.Kind kind, BlockVector3 pos, SideEffectSet effects,
            java.util.function.Supplier<EditValue> target) {
        ServerLevel level = (ServerLevel) getWorld();
        if (level.getServer().isSameThread()) {
            throw new UnsupportedOperationException("Use the asynchronous FabricEdits API on the server thread");
        }
        if (editContext == null) {
            throw new UnsupportedOperationException("Native edits require a bound current actor");
        }
        boolean replay = editContext.source() == EditActorContext.Source.UNDO
                || editContext.source() == EditActorContext.Source.REDO;
        EditActorContext unit = new EditActorContext(editContext.actorId(), editContext.actorName(),
                editContext.source(), java.util.UUID.randomUUID(),
                replay ? editContext.parentId() : editContext.operationId(), editContext.originalActorId(),
                editContext.dimension(), effects, editContext.cancellation().child(), editContext.operationId());
        var completion = TaskManager.taskManager().sync(() -> FabricEdits.nativeService(level.getServer())
                .submitNative(unit, kind, pos, target.get(), nativeHistory));
        try {
            CommitReceipt receipt = completion.toCompletableFuture().join();
            lastNativeReceipt = receipt;
            if (receipt.outcome() == CommitReceipt.Outcome.CONFLICT) {
                throw new IllegalStateException("Native edit conflicted before publication");
            }
            return !receipt.applied().isEmpty();
        } catch (java.util.concurrent.CompletionException failure) {
            if (failure.getCause() instanceof com.fastasyncworldedit.fabric.edit.FabricCommitCoordinator.CommitFailure commit) {
                lastNativeReceipt = commit.receipt();
                var unsupported = unsupportedBlock(commit);
                if (unsupported != null) {
                    skipped.add(unsupported);
                    return false;
                }
            }
            // Each cell has its own cancellation, so any other failure must stop the operation here.
            editContext.cancellation().cancel();
            throw failure;
        }
    }

    // Only a clean pre-write refusal for lack of lifecycle support is skippable.
    private static com.fastasyncworldedit.fabric.edit.UnsupportedBlockException unsupportedBlock(
            com.fastasyncworldedit.fabric.edit.FabricCommitCoordinator.CommitFailure commit) {
        CommitReceipt receipt = commit.receipt();
        if (receipt.outcome() != CommitReceipt.Outcome.FAILED_BEFORE_WRITE || !receipt.applied().isEmpty()) {
            return null;
        }
        for (Throwable cause = commit.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof com.fastasyncworldedit.fabric.edit.UnsupportedBlockException unsupported) {
                return unsupported;
            }
        }
        return null;
    }

    // Only owner-thread mutation/history code sees this ungated before image.
    private EditValue nativeValue(BlockPos pos) {
        BlockEntity tile = getWorld().getBlockEntity(pos);
        return new EditValue(FabricAdapter.adapt(getWorld().getBlockState(pos)).getAsString(), tile == null
                ? null : FrozenNbt.capture(tile.saveWithFullMetadata(getWorld().registryAccess())));
    }

    @Override
    public int hashCode() {
        return getWorld().hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if ((o instanceof FabricWorld other)) {
            Level otherWorld = other.worldRef.get();
            Level thisWorld = worldRef.get();
            return otherWorld != null && otherWorld.equals(thisWorld);
        } else if (o instanceof com.sk89q.worldedit.world.World) {
            return ((com.sk89q.worldedit.world.World) o).getName().equals(getName());
        } else {
            return false;
        }
    }

    @Override
    public List<? extends Entity> getEntities(Region region) {
        NativeClaims.unsupported("entity snapshots");
        final Level world = getWorld();
        AABB box = new AABB(
            FabricAdapter.toVec3(region.getMinimumPoint()),
            FabricAdapter.toVec3(region.getMaximumPoint().add(BlockVector3.ONE))
        );
        List<net.minecraft.world.entity.Entity> nmsEntities = world.getEntities(
            (net.minecraft.world.entity.Entity) null,
            box,
            e -> region.contains(FabricAdapter.adapt(e.blockPosition()))
        );
        return nmsEntities.stream()
            .map(FabricEntity::new)
            .collect(ImmutableList.toImmutableList());
    }

    @Override
    public List<? extends Entity> getEntities() {
        NativeClaims.unsupported("entity snapshots");
        final Level world = getWorld();
        if (!(world instanceof ServerLevel)) {
            return Collections.emptyList();
        }
        return Streams.stream(((ServerLevel) world).getAllEntities())
            .map(FabricEntity::new)
            .collect(ImmutableList.toImmutableList());
    }

    @Nullable
    @Override
    public Entity createEntity(Location location, BaseEntity entity) {
        return TaskManager.taskManager().sync(() -> spawn(location, entity, null));
    }

    /** Redo of a creation and undo of a removal: the entity comes back as itself, UUID included. */
    @Nullable
    @Override
    public Entity createEntity(Location location, BaseEntity entity, UUID uuid) {
        return TaskManager.taskManager().sync(() -> spawn(location, entity, uuid));
    }

    /** Undo of a creation: remove exactly that entity, wherever it went; absent means already gone. */
    @Override
    public void removeEntity(int x, int y, int z, UUID uuid) {
        TaskManager.taskManager().sync(() -> {
            NativeClaims.unsupported("entity removal");
            net.minecraft.world.entity.Entity target = ((ServerLevel) getWorld()).getEntity(uuid);
            if (target != null && !(target instanceof net.minecraft.world.entity.player.Player)) {
                target.discard();
            }
            return null;
        });
    }

    // Owner thread only. A forced UUID already in use is refused rather than duplicated.
    @Nullable
    private Entity spawn(Location location, BaseEntity entity, @Nullable UUID uuid) {
        NativeClaims.unsupported("entity creation");
        ServerLevel world = (ServerLevel) getWorld();
        if (uuid != null && world.getEntity(uuid) != null) {
            return null;
        }
        String entityId = entity.getType().id();
        final Optional<EntityType<?>> entityType = world.registryAccess().lookupOrThrow(Registries.ENTITY_TYPE).getOptional(Identifier.parse(entityId));
        if (entityType.isEmpty()) {
            return null;
        }
        LinCompoundTag linTag = entity.getNbt();
        net.minecraft.nbt.CompoundTag tag;
        if (linTag != null) {
            tag = NBTConverter.toNative(linTag);
            removeUnwantedEntityTagsRecursively(tag);
        } else {
            tag = new net.minecraft.nbt.CompoundTag();
        }
        tag.putString("id", entityId);
        if (uuid != null) {
            tag.putIntArray("UUID", net.minecraft.core.UUIDUtil.uuidToIntArray(uuid));
        }

        net.minecraft.world.entity.Entity createdEntity = EntityType.loadEntityRecursive(entityType.get(), tag, world, EntitySpawnReason.COMMAND, (loadedEntity) -> {
            loadedEntity.absSnapTo(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            return loadedEntity;
        });
        if (createdEntity != null) {
            world.addFreshEntityWithPassengers(createdEntity);
            return new FabricEntity(createdEntity);
        }
        return null;
    }

    private void removeUnwantedEntityTagsRecursively(net.minecraft.nbt.CompoundTag tag) {
        for (String name : Constants.NO_COPY_ENTITY_NBT_FIELDS) {
            tag.remove(name);
        }

        // Adapted from net.minecraft.world.entity.EntityType#loadEntityRecursive
        tag.getList("Passengers").ifPresent(nbttaglist -> {
            for (int i = 0; i < nbttaglist.size(); ++i) {
                removeUnwantedEntityTagsRecursively(nbttaglist.getCompoundOrEmpty(i));
            }
        });
    }

    @Override
    public Mask createLiquidMask() {
        return new LiquidMask(this);
    }

    public boolean isValid() {
        return worldRef.get() != null;
    }

    @Override
    public IChunkGet get(int chunkX, int chunkZ) {
        throw new UnsupportedOperationException(
                "Live queue access requires FabricEdits snapshots; use the asynchronous API or /fawe-coordinated");
    }

    @Override
    public void refreshChunk(int chunkX, int chunkZ) {
        getWorld().getChunk(chunkX, chunkZ);
    }

    @Override
    public void sendFakeChunk(@Nullable Player player, ChunkPacket packet) {
        // Not supported on Fabric without additional networking hooks.
    }

    @Override
    public void flush() {
        worldNativeAccess.flush();
        reportSkipped();
    }

    private static final int SKIPPED_LISTED = 8;

    // One summary per flush; the cells were left unchanged and are absent from history.
    private void reportSkipped() {
        List<com.fastasyncworldedit.fabric.edit.UnsupportedBlockException> cells;
        synchronized (skipped) {
            if (skipped.isEmpty()) {
                return;
            }
            cells = List.copyOf(skipped);
            skipped.clear();
        }
        if (actor == null) {
            return;
        }
        StringBuilder text = new StringBuilder("Skipped ").append(cells.size())
                .append(cells.size() == 1 ? " unsupported block" : " unsupported blocks")
                .append(", left unchanged (no lifecycle support yet): ");
        for (int i = 0; i < Math.min(cells.size(), SKIPPED_LISTED); i++) {
            var cell = cells.get(i);
            text.append(i == 0 ? "" : "; ").append(cell.block()).append(" at ")
                    .append(cell.x()).append(',').append(cell.y()).append(',').append(cell.z());
        }
        if (cells.size() > SKIPPED_LISTED) {
            text.append("; and ").append(cells.size() - SKIPPED_LISTED).append(" more");
        }
        actor.print(com.sk89q.worldedit.util.formatting.text.TextComponent.of(text.toString(),
                com.sk89q.worldedit.util.formatting.text.format.TextColor.YELLOW));
    }

    @Override
    public boolean tile(int x, int y, int z, FaweCompoundTag tile) throws WorldEditException {
        return nativeWrite(EditDelta.Kind.BLOCK, BlockVector3.at(x, y, z), SideEffectSet.none(), () ->
                new EditValue(FabricAdapter.adapt(getWorld().getBlockState(new BlockPos(x, y, z))).getAsString(),
                        FrozenNbt.capture(NBTConverter.toNative(tile.linTag()))));
    }

    private static final class LiquidMask extends AbstractExtentMask {
        private LiquidMask(Extent extent) {
            super(extent);
        }

        @Override
        public boolean test(Extent extent, BlockVector3 position) {
            return FabricAdapter.adapt(extent.getBlock(position)).getBlock() instanceof LiquidBlock;
        }

        @Override
        public boolean test(BlockVector3 vector) {
            return test(getExtent(), vector);
        }

        @Override
        public Mask copy() {
            return new LiquidMask(getExtent());
        }
    }

}
