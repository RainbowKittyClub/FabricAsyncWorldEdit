package com.fastasyncworldedit.fabric.edit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.storage.TagValueInput;

/** Owner-thread vanilla access; unsupported callbacks and payload families are refused before writes. */
public final class FabricWorldAccess {
    private static final Set<String> SUPPORTED_BLOCKS = Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:stone",
            "minecraft:dirt", "minecraft:cobblestone", "minecraft:glass", "minecraft:glowstone",
            "minecraft:oak_log", "minecraft:birch_log", "minecraft:stripped_oak_log",
            "minecraft:acacia_log", "minecraft:jungle_log", "minecraft:redstone_lamp",
            "minecraft:oak_planks", "minecraft:grass_block", "minecraft:water",
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:furnace",
            "minecraft:bell", "minecraft:lectern", "minecraft:gold_block", "minecraft:diamond_block");
    private final MinecraftServer server;
    private FabricStateMapping mapping;
    private boolean mappingValid = true;
    private final Set<CompletableFuture<Void>> maintenance = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean stopping;
    private long installedSections;
    private long fallbackWrites;
    private long relitUnits;
    private long sentUnits;

    /** Bind one loaded registry generation; no live object is handed to detached preparation. */
    public FabricWorldAccess(MinecraftServer server, FabricStateMapping mapping) {
        this.server = server;
        this.mapping = mapping;
        owner();
    }

    /** Refuse old proposals from the start of reload, including a failed reload. */
    public void invalidateMapping() {
        owner();
        mappingValid = false;
    }

    /** Install a complete fresh generation after reload has finished. */
    public void replaceMapping(FabricStateMapping replacement) {
        owner();
        mapping = Objects.requireNonNull(replacement);
        mappingValid = true;
    }

    /** Resolve the current dimension only on the owner thread. */
    public ServerLevel level(String dimension) {
        owner();
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
        if (level == null) {
            throw new IllegalArgumentException("Dimension is unavailable: " + dimension);
        }
        return level;
    }

    /** Read a loaded chunk without triggering managed blocking or generation. */
    public LevelChunk loadedChunk(ServerLevel level, int chunkX, int chunkZ) {
        owner();
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            throw new IllegalStateException("Required ticketed chunk is not loaded: " + chunkX + "," + chunkZ);
        }
        refuseAttachments(chunk);
        return chunk;
    }

    // Fabric's pinned internal enumeration is required to fail closed for unknown payload owners.
    static void refuseAttachments(Object target) {
        if (!(target instanceof net.fabricmc.fabric.impl.attachment.AttachmentTargetImpl attachments)) {
            throw new UnsupportedOperationException("Attachment enumeration capability is unavailable");
        }
        var values = attachments.fabric_getAttachments();
        if (values != null && !values.isEmpty()) {
            throw new UnsupportedOperationException("Attachment lifecycle/restore codecs are not installed");
        }
    }

    /** Compare every declared dependency plus every intended before-value immediately before commit. */
    public boolean matches(PreparedChunkEdit proposal, ServerLevel level) {
        owner();
        if (!mappingValid || proposal.registryGeneration() != mapping.generation()) {
            return false;
        }
        for (PreparedChunkEdit.ReadDependency dependency : proposal.reads()) {
            LevelChunk chunk = loadedChunk(level, dependency.x() >> 4, dependency.z() >> 4);
            if (((ChunkRevision) chunk).worldedit$editRevision() != dependency.revision()
                    || !Objects.equals(dependency.value(), read(level, dependency.kind(), dependency.x(),
                            dependency.y(), dependency.z(), dependency.key()))) {
                return false;
            }
        }
        for (EditDelta delta : proposal.writes()) {
            if (!Objects.equals(delta.before(), read(level, delta))) {
                return false;
            }
        }
        return true;
    }

    /** Validate the entire bounded unit before any mutation, not after a partial unsupported write. */
    public void validateSupported(PreparedChunkEdit proposal, ServerLevel level) {
        owner();
        if (proposal.context().sideEffects().shouldApply(SideEffect.NEIGHBORS)
                || proposal.context().sideEffects().shouldApply(SideEffect.UPDATE)
                || proposal.context().sideEffects().shouldApply(SideEffect.VALIDATION)) {
            throw new UnsupportedOperationException("Cross-cell callbacks require a declared write footprint");
        }
        validatePayloads(proposal, level);
    }

    /** Validate the one-cell native path without substituting section-publication side effects. */
    public void validateNative(PreparedChunkEdit proposal, ServerLevel level) {
        owner();
        if (proposal.writes().size() != 1
                || proposal.context().sideEffects().shouldApply(SideEffect.VALIDATION)) {
            throw new UnsupportedOperationException("Native targets must be validated before authorization");
        }
        validatePayloads(proposal, level);
    }

    // Both writers require the same payload/lifecycle capabilities before changing anything.
    private void validatePayloads(PreparedChunkEdit proposal, ServerLevel level) {
        Set<String> cells = new HashSet<>();
        int heavy = 0;
        for (EditDelta delta : proposal.writes()) {
            if (!cells.add(delta.kind() + ":" + delta.x() + ":" + delta.y() + ":" + delta.z() + ":" + delta.key())) {
                throw new IllegalArgumentException("Duplicate proposed cell");
            }
            if (delta.y() < level.getMinY() || delta.y() > level.getMaxY()) {
                throw new IllegalArgumentException("Write outside dimension height");
            }
            switch (delta.kind()) {
                case BLOCK -> {
                    for (EditValue value : new EditValue[] {delta.before(), delta.after()}) {
                        var state = mapping.nativeState(value.id());
                        if (!SUPPORTED_BLOCKS.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) {
                            throw new UnsupportedOperationException("No lifecycle capability for " + value.id());
                        }
                        if (value.nbt() != null && !state.hasBlockEntity()) {
                            throw new IllegalArgumentException("NBT on a block without a block entity");
                        }
                    }
                    if (mapping.nativeState(delta.before().id()).hasBlockEntity()
                            || mapping.nativeState(delta.after().id()).hasBlockEntity()) {
                        heavy++;
                    }
                }
                case BIOME -> {
                    if ((delta.x() & 3) != 0 || (delta.y() & 3) != 0 || (delta.z() & 3) != 0) {
                        throw new IllegalArgumentException("Biome coordinates must be quart-cell origins");
                    }
                    level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(
                            ResourceKey.create(Registries.BIOME, Identifier.parse(delta.after().id())));
                }
                default -> throw new UnsupportedOperationException("No writer registered for " + delta.kind());
            }
        }
        if (heavy > 64) {
            throw new IllegalArgumentException("NBT-heavy publication units are limited to 64 cells");
        }
    }

    /** Capture an actual value; this is also used after a write throws. */
    public EditValue read(ServerLevel level, EditDelta delta) {
        return read(level, delta.kind(), delta.x(), delta.y(), delta.z(), delta.key());
    }

    /** Capture a detached block/NBT or biome observation on the owner thread. */
    public EditValue read(ServerLevel level, EditDelta.Kind kind, int x, int y, int z, String key) {
        LevelChunk chunk = loadedChunk(level, x >> 4, z >> 4);
        BlockPos pos = new BlockPos(x, y, z);
        return switch (kind) {
            case BLOCK -> {
                var state = chunk.getBlockState(pos);
                var blockEntity = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
                if (blockEntity != null) {
                    refuseAttachments(blockEntity);
                }
                yield new EditValue(FabricAdapter.adapt(state).getAsString(), blockEntity == null ? null
                        : FrozenNbt.capture(blockEntity.saveWithFullMetadata(level.registryAccess())));
            }
            case BIOME -> new EditValue(chunk.getNoiseBiome(x >> 2, y >> 2, z >> 2)
                    .unwrapKey().orElseThrow().identifier().toString(), null);
            default -> throw new UnsupportedOperationException("No reader registered for " + kind);
        };
    }

    /**
     * Owner-thread route check for whole-section publication. A proposal is eligible only when
     * every delta is a simple block, no cell has a block entity on either side, and every touched
     * section currently contains only minecraft-namespace blocks. Mixed or lifecycle-heavy
     * proposals use the ordinary per-cell route instead.
     */
    public boolean directSectionEligible(ServerLevel level, List<EditDelta> deltas) {
        owner();
        if (deltas.isEmpty()) return false;
        LevelChunk chunk = null;
        Set<Integer> checked = new HashSet<>();
        for (EditDelta delta : deltas) {
            if (delta.kind() != EditDelta.Kind.BLOCK) return false;
            if (mapping.nativeState(delta.before().id()).hasBlockEntity()
                    || mapping.nativeState(delta.after().id()).hasBlockEntity()) {
                return false;
            }
            if (chunk == null) {
                chunk = loadedChunk(level, delta.x() >> 4, delta.z() >> 4);
            } else if (delta.x() >> 4 != chunk.getPos().x() || delta.z() >> 4 != chunk.getPos().z()) {
                return false;
            }
            int sectionY = delta.y() >> 4;
            if (!checked.add(sectionY)) continue;
            var section = chunk.getSection(chunk.getSectionIndex(delta.y()));
            if (section.maybeHas(state -> !minecraftNamespace(state))) return false;
        }
        return true;
    }

    /** Partition simple cells into section installs and lifecycle-heavy writes into single-cell units. */
    public List<List<EditDelta>> publicationUnits(List<EditDelta> deltas) {
        owner();
        Map<Integer, List<EditDelta>> sections = new LinkedHashMap<>();
        List<List<EditDelta>> units = new ArrayList<>();
        for (EditDelta delta : deltas) {
            if (delta.kind() == EditDelta.Kind.BLOCK
                    && !mapping.nativeState(delta.before().id()).hasBlockEntity()
                    && !mapping.nativeState(delta.after().id()).hasBlockEntity()) {
                sections.computeIfAbsent(delta.y() >> 4, ignored -> new ArrayList<>()).add(delta);
            } else {
                units.add(List.of(delta));
            }
        }
        sections.values().forEach(cells -> units.add(List.copyOf(cells)));
        return List.copyOf(units);
    }

    /**
     * Publish a prevalidated unit; receipt capture belongs in the caller's finally block.
     *
     * @param directSections true only when {@link #directSectionEligible} approved the proposal.
     *        The strict path refuses to silently switch routes while a provider holds
     *        section-scoped state; false writes every cell through the ordinary lifecycle path.
     */
    public void writeUnit(ServerLevel level, List<EditDelta> deltas, SideEffectSet effects,
            boolean directSections) {
        owner();
        if (deltas.isEmpty()) return;
        EditDelta first = deltas.getFirst();
        if (directSections) {
            for (EditDelta delta : deltas) {
                if (delta.kind() != EditDelta.Kind.BLOCK
                        || mapping.nativeState(delta.before().id()).hasBlockEntity()
                        || mapping.nativeState(delta.after().id()).hasBlockEntity()) {
                    throw new IllegalStateException("Direct-section route received a lifecycle cell");
                }
            }
            LevelChunk chunk = loadedChunk(level, first.x() >> 4, first.z() >> 4);
            var section = chunk.getSection(chunk.getSectionIndex(first.y()));
            if (section.maybeHas(state -> !minecraftNamespace(state))) {
                throw new IllegalStateException("Direct-section route lost section eligibility");
            }
            FabricSectionPublication.install(level, chunk, deltas, mapping);
            installedSections++;
        } else {
            for (EditDelta delta : deltas) {
                write(level, delta, effects);
                fallbackWrites++;
            }
        }
    }

    /** Await vanilla light work, then construct updates on the owner thread for each watcher. */
    public synchronized CompletionStage<Void> completePublication(ServerLevel level, CommitReceipt receipt, boolean send) {
        if (stopping) return CompletableFuture.failedFuture(new IllegalStateException("Publication maintenance stopped"));
        CompletableFuture<Void> result = new CompletableFuture<>();
        maintenance.add(result);
        result.whenComplete((unused, failure) -> maintenance.remove(result));
        server.execute(() -> {
            if (stopping || result.isDone()) {
                result.completeExceptionally(new IllegalStateException("Publication maintenance stopped"));
                return;
            }
            if (receipt.applied().isEmpty()) {
                result.complete(null);
                return;
            }
            CompletableFuture<?> light;
            try {
                light = level.getChunkSource().getLightEngine().waitForPendingTasks(receipt.chunkX(), receipt.chunkZ());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
                return;
            }
            light.whenComplete((unused, failure) -> server.execute(() -> {
                        if (result.isDone()) return;
                        if (failure != null) {
                            result.completeExceptionally(failure);
                            return;
                        }
                        try {
                            owner();
                            relitUnits++;
                            LevelChunk chunk = loadedChunk(level, receipt.chunkX(), receipt.chunkZ());
                            if (send) {
                                for (var player : level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false)) {
                                    com.sk89q.worldedit.fabric.mixin.InvokerPlayerChunkSender.worldedit$sendChunk(
                                            player.connection, level, chunk);
                                }
                                sentUnits++;
                            }
                            result.complete(null);
                        } catch (Throwable problem) {
                            result.completeExceptionally(problem);
                        }
                    }));
        });
        return result.minimalCompletionStage();
    }

    /** Settle outstanding maintenance before the owner thread stops servicing callbacks. */
    public synchronized void stop() {
        owner();
        stopping = true;
        maintenance.forEach(future -> future.completeExceptionally(new IllegalStateException("Server stopping")));
    }

    /** Owner-thread counters establish which publication and completion paths ran. */
    public Map<String, Long> statistics() {
        owner();
        return Map.of("sections", installedSections, "fallbackWrites", fallbackWrites,
                "relit", relitUnits, "sent", sentUnits, "maintenance", (long) maintenance.size());
    }

    /** Apply only the authorized cell through WNA; the coordinator captures even a throwing write. */
    public void writeNative(ServerLevel level, EditDelta delta, SideEffectSet effects) {
        owner();
        if (delta.kind() == EditDelta.Kind.BIOME) {
            write(level, delta, effects);
            return;
        }
        if (delta.kind() != EditDelta.Kind.BLOCK) {
            throw new UnsupportedOperationException("No native writer for " + delta.kind());
        }
        var nativeAccess = new com.sk89q.worldedit.fabric.internal.FabricWorldNativeAccess(
                new java.lang.ref.WeakReference<>(level));
        var state = com.sk89q.worldedit.world.block.BlockState.get(delta.after().id());
        var block = delta.after().nbt() == null ? state.toBaseBlock() : state.toBaseBlock(
                com.sk89q.worldedit.fabric.internal.NBTConverter.fromNative(delta.after().nbt().thaw()));
        BlockPos pos = new BlockPos(delta.x(), delta.y(), delta.z());
        try {
            // Same-type state changes retain NBT; history replays tile data before block properties.
            var tile = level.getBlockEntity(pos);
            if (tile instanceof net.minecraft.world.Clearable clearable
                    && level.getBlockState(pos).getBlock() != mapping.nativeState(delta.after().id()).getBlock()) {
                clearable.clearContent();
                tile.setChanged();
            }
            nativeAccess.setBlock(com.sk89q.worldedit.math.BlockVector3.at(delta.x(), delta.y(), delta.z()),
                    block, effects);
        } catch (com.sk89q.worldedit.WorldEditException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Guarded vanilla fallback; callers must have validated and authorized this exact delta. */
    public void write(ServerLevel level, EditDelta delta, SideEffectSet effects) {
        LevelChunk chunk = loadedChunk(level, delta.x() >> 4, delta.z() >> 4);
        BlockPos pos = new BlockPos(delta.x(), delta.y(), delta.z());
        switch (delta.kind()) {
            case BLOCK -> {
                var oldState = chunk.getBlockState(pos);
                var state = mapping.nativeState(delta.after().id());
                chunk.setBlockState(pos, state, Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                if (state.hasBlockEntity()) {
                    var entity = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
                    if (entity == null) {
                        throw new IllegalStateException("Block entity was not created for " + pos);
                    }
                    if (delta.after().nbt() != null) {
                        entity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,
                                level.registryAccess(), delta.after().nbt().thaw()));
                        entity.setChanged();
                    }
                }
                if (effects.shouldApply(SideEffect.POI_UPDATE)) {
                    level.updatePOIOnBlockStateChange(pos, oldState, state);
                }
            }
            case BIOME -> {
                var biome = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(
                        ResourceKey.create(Registries.BIOME, Identifier.parse(delta.after().id())));
                var palette = (PalettedContainer<Holder<net.minecraft.world.level.biome.Biome>>)
                        chunk.getSection(chunk.getSectionIndex(delta.y())).getBiomes();
                ((ChunkRevision) chunk).worldedit$advanceEditRevision();
                palette.getAndSetUnchecked((delta.x() >> 2) & 3, (delta.y() >> 2) & 3,
                        (delta.z() >> 2) & 3, biome);
                chunk.markUnsaved();
            }
            default -> throw new UnsupportedOperationException("No writer registered for " + delta.kind());
        }
    }

    private static boolean minecraftNamespace(net.minecraft.world.level.block.state.BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft");
    }

    // Workers may only use detached snapshots; even a loaded live read is forbidden.
    private void owner() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Live world access from a preparation worker");
        }
    }
}
