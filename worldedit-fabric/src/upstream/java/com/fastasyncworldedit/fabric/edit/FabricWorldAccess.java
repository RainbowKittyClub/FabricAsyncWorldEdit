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
    private final MinecraftServer server;
    private final FabricOptimizations optimizations = FabricOptimizations.detect();
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
            var ids = values.keySet().stream().map(type -> type.identifier().toString())
                    .filter(id -> !(target instanceof LevelChunk chunk)
                            || !PositionDataRegistry.get(((ServerLevel) chunk.getLevel()).getServer()).owns(id))
                    .sorted().toList();
            if (!ids.isEmpty()) {
                throw new UnsupportedOperationException("Attachment lifecycle/restore codecs are not installed for "
                        + describe(target) + " " + ids);
            }
        }
    }

    /** Preserve fail-closed attachment checks for native full-block/clipboard reads too. */
    public static void refuseAttachmentsForRead(ServerLevel level, BlockPos pos) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Native reads require the owner thread");
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) throw new IllegalStateException("Native read chunk is not loaded at " + pos);
        refuseAttachments(chunk);
        var entity = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
        if (entity != null) refuseAttachments(entity);
    }

    private static String describe(Object target) {
        if (target instanceof LevelChunk chunk) {
            return "chunk " + chunk.getPos().x() + "," + chunk.getPos().z();
        }
        if (target instanceof net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
            BlockPos pos = blockEntity.getBlockPos();
            return "block entity at " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }
        if (target instanceof net.minecraft.world.entity.Entity entity) {
            BlockPos pos = entity.blockPosition();
            return "entity " + entity.getUUID() + " at " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }
        return target.getClass().getSimpleName();
    }

    /** Compare every declared dependency plus every intended before-value immediately before commit. */
    public boolean matches(PreparedChunkEdit proposal, ServerLevel level) {
        return matches(proposal, level, true);
    }

    /**
     * As {@link #matches(PreparedChunkEdit, ServerLevel)}; without revisions only values count, for
     * native units whose targets are absolute, so changes elsewhere in the chunk do not conflict.
     */
    public boolean matches(PreparedChunkEdit proposal, ServerLevel level, boolean revisions) {
        return matches(proposal, level, revisions, false);
    }

    /**
     * As {@link #matches(PreparedChunkEdit, ServerLevel, boolean)}; with {@code rebase}, a changed
     * value still matches when it is {@link #rebasable}, since an absolute target overwrites it as found.
     */
    public boolean matches(PreparedChunkEdit proposal, ServerLevel level, boolean revisions, boolean rebase) {
        owner();
        if (!mappingValid || proposal.registryGeneration() != mapping.generation()) {
            return false;
        }
        for (PreparedChunkEdit.ReadDependency dependency : proposal.reads()) {
            LevelChunk chunk = loadedChunk(level, dependency.x() >> 4, dependency.z() >> 4);
            if (revisions && ((ChunkRevision) chunk).worldedit$editRevision() != dependency.revision()) {
                return false;
            }
            EditValue current = read(level, dependency.kind(), dependency.x(), dependency.y(), dependency.z(),
                    dependency.key());
            if (!Objects.equals(dependency.value(), current)
                    && !(rebase && rebasable(dependency.kind(), dependency.value(), current))) {
                return false;
            }
        }
        for (EditDelta delta : proposal.writes()) {
            EditValue current = read(level, delta);
            if (!Objects.equals(delta.before(), current) && !(rebase && rebasable(delta.kind(), delta.before(), current))) {
                return false;
            }
        }
        return true;
    }

    // Neither value carries NBT and the current block is supported without a block entity: nothing
    // that was authorized, counted or inherited at the snapshot depends on the difference.
    private boolean rebasable(EditDelta.Kind kind, EditValue snapshot, EditValue current) {
        if (kind == EditDelta.Kind.BIOME) {
            return true;
        }
        if (kind != EditDelta.Kind.BLOCK || snapshot == null || current == null
                || snapshot.nbt() != null || current.nbt() != null) {
            return false;
        }
        var state = mapping.nativeState(current.id());
        return !state.hasBlockEntity()
                && LifecycleDeclarations.supports(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
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

    /** Validate a native unit without substituting section-publication side effects. */
    public void validateNative(PreparedChunkEdit proposal, ServerLevel level) {
        owner();
        if (proposal.writes().isEmpty()) {
            throw new UnsupportedOperationException("A native unit writes at least one cell");
        }
        validatePayloads(proposal, level);
    }

    /** Whether these side effects run callbacks that may change other cells. */
    public static boolean hasCallbacks(SideEffectSet effects) {
        return effects.shouldApply(SideEffect.NEIGHBORS) || effects.shouldApply(SideEffect.UPDATE);
    }

    /** These side effects with the callbacks left for {@link #runCallbacks}. */
    public static SideEffectSet withoutCallbacks(SideEffectSet effects) {
        return effects.with(SideEffect.NEIGHBORS, SideEffect.State.OFF).with(SideEffect.UPDATE, SideEffect.State.OFF);
    }

    /**
     * Run a unit's placement and neighbour callbacks once all its cells are written, in write order.
     * As in vanilla, {@code onPlace} runs only while the cell still holds the block written there.
     */
    public void runCallbacks(ServerLevel level, List<CommitReceipt.AppliedDelta> applied, SideEffectSet effects) {
        owner();
        var nativeAccess = new com.sk89q.worldedit.fabric.internal.FabricWorldNativeAccess(
                new java.lang.ref.WeakReference<>(level));
        for (CommitReceipt.AppliedDelta entry : applied) {
            EditDelta delta = entry.delta();
            if (delta.kind() != EditDelta.Kind.BLOCK) {
                continue;
            }
            BlockPos pos = new BlockPos(delta.x(), delta.y(), delta.z());
            var old = mapping.nativeState(delta.before().id());
            var now = level.getBlockState(pos);
            if (effects.shouldApply(SideEffect.UPDATE) && now.is(mapping.nativeState(delta.after().id()).getBlock())) {
                now.onPlace(level, pos, old, false);
                now = level.getBlockState(pos);
            }
            if (effects.shouldApply(SideEffect.NEIGHBORS)) {
                nativeAccess.notifyNeighbors(pos, old, now);
                nativeAccess.updateNeighbors(pos, old, now, 512);
            }
        }
    }

    /** Whether a block type, by registry id, has verified lifecycle support (the base set or a mod's declaration). */
    public static boolean lifecycleSupported(String blockId) {
        return LifecycleDeclarations.supports(blockId);
    }

    /** The lifecycle refusal this delta would meet at publication, or null. */
    public UnsupportedBlockException unsupported(EditDelta delta) {
        if (delta.kind() != EditDelta.Kind.BLOCK) {
            return null;
        }
        for (EditValue value : new EditValue[] {delta.before(), delta.after()}) {
            var state = mapping.nativeState(value.id());
            if (!LifecycleDeclarations.supports(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) {
                return new UnsupportedBlockException(value.id(), delta.x(), delta.y(), delta.z());
            }
        }
        return null;
    }

    /** Real NBT and owners without an empty-stability guarantee retain the 64 heavy-cell bound. */
    public boolean blockEntityCell(EditDelta delta) {
        return delta.kind() == EditDelta.Kind.BLOCK && (mapping.nativeState(delta.before().id()).hasBlockEntity()
                || mapping.nativeState(delta.after().id()).hasBlockEntity()
                || !PositionDataRegistry.stableWhenEmpty(mapping.nativeState(delta.before().id()))
                || !PositionDataRegistry.stableWhenEmpty(mapping.nativeState(delta.after().id()))
                || PositionDataTransport.blockEntity(delta.before().nbt()) != null
                || PositionDataTransport.blockEntity(delta.after().nbt()) != null
                || PositionDataTransport.valuesPresent(delta.before().nbt())
                || PositionDataTransport.valuesPresent(delta.after().nbt()));
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
                        if (!LifecycleDeclarations.supports(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) {
                            throw new UnsupportedBlockException(value.id(), delta.x(), delta.y(), delta.z());
                        }
                        if (PositionDataTransport.blockEntity(value.nbt()) != null && !state.hasBlockEntity()) {
                            throw new IllegalArgumentException("NBT on a block without a block entity");
                        }
                        PositionDataRegistry.get(server).prepare(level, state,
                                PositionDataTransport.positions(value.nbt()));
                    }
                    if (blockEntityCell(delta)) {
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
                yield new EditValue(FabricAdapter.adapt(state).getAsString(), PositionDataTransport.combine(
                        blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess()),
                        PositionDataRegistry.get(server).read(level, pos, state)));
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
            var before = mapping.nativeState(delta.before().id());
            var after = mapping.nativeState(delta.after().id());
            if (blockEntityCell(delta) || !directSafe(before) || !directSafe(after)) {
                return false;
            }
            if (chunk == null) {
                chunk = loadedChunk(level, delta.x() >> 4, delta.z() >> 4);
                var attachments = ((net.fabricmc.fabric.impl.attachment.AttachmentTargetImpl) chunk)
                        .fabric_getAttachments();
                // No registered or unknown stored data may be skipped by a direct installation.
                if (attachments != null && !attachments.isEmpty()) return false;
            } else if (delta.x() >> 4 != chunk.getPos().x() || delta.z() >> 4 != chunk.getPos().z()) {
                return false;
            }
            int sectionY = delta.y() >> 4;
            if (!checked.add(sectionY)) continue;
            var section = chunk.getSection(chunk.getSectionIndex(delta.y()));
            if (section.maybeHas(state -> !directSafe(state))) return false;
        }
        return true;
    }

    /** Partition simple cells into section installs and lifecycle-heavy writes into single-cell units. */
    public List<List<EditDelta>> publicationUnits(List<EditDelta> deltas) {
        owner();
        Map<Integer, List<EditDelta>> sections = new LinkedHashMap<>();
        List<List<EditDelta>> units = new ArrayList<>();
        for (EditDelta delta : deltas) {
            if (delta.kind() == EditDelta.Kind.BLOCK && !blockEntityCell(delta)) {
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
     *        section-scoped state.
     * @param groupRoute true when no provider holds section-scoped state (no token can be live),
     *        allowing each publication unit to choose the bulk section install independently;
     *        lifecycle cells and non-minecraft sections fall through to per-cell writes.
     */
    public void writeUnit(ServerLevel level, List<EditDelta> deltas, SideEffectSet effects,
            boolean directSections, boolean groupRoute) {
        owner();
        if (deltas.isEmpty()) return;
        EditDelta first = deltas.getFirst();
        if (directSections) {
            if (!directSectionEligible(level, deltas)) {
                throw new IllegalStateException("Direct-section route lost full payload/section eligibility");
            }
            LevelChunk chunk = loadedChunk(level, first.x() >> 4, first.z() >> 4);
            var section = chunk.getSection(chunk.getSectionIndex(first.y()));
            if (section.maybeHas(state -> !directSafe(state))) {
                throw new IllegalStateException("Direct-section route lost section eligibility");
            }
            FabricSectionPublication.install(level, chunk, deltas, mapping, optimizations, effects);
            installedSections++;
        } else if (groupRoute && first.kind() == EditDelta.Kind.BLOCK
                && !mapping.nativeState(first.before().id()).hasBlockEntity()
                && !mapping.nativeState(first.after().id()).hasBlockEntity()) {
            LevelChunk chunk = loadedChunk(level, first.x() >> 4, first.z() >> 4);
            if (!directSectionEligible(level, deltas)) {
                for (EditDelta delta : deltas) {
                    write(level, delta, effects);
                    fallbackWrites++;
                }
            } else {
                FabricSectionPublication.install(level, chunk, deltas, mapping, optimizations, effects);
                installedSections++;
            }
        } else {
            for (EditDelta delta : deltas) {
                write(level, delta, effects);
                fallbackWrites++;
            }
        }
    }

    /** The installed optimization mods' verdict, and their pinned adapters. */
    public FabricOptimizations optimizations() {
        return optimizations;
    }

    /** Await the light engine's barrier, then construct updates on the owner thread for each watcher. */
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
                light = optimizations.relightBarrier(level, receipt.chunkX(), receipt.chunkZ());
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
        var nbt = PositionDataTransport.blockEntity(delta.after().nbt());
        var block = nbt == null ? state.toBaseBlock() : state.toBaseBlock(
                com.sk89q.worldedit.fabric.internal.NBTConverter.fromNative(nbt));
        BlockPos pos = new BlockPos(delta.x(), delta.y(), delta.z());
        if (effects.shouldApply(SideEffect.VALIDATION)) {
            // Corrected against the cells written so far; a correction to an unsupported block is left out.
            var target = mapping.nativeState(delta.after().id());
            var valid = Block.updateFromNeighbourShapes(target, level, pos);
            if (valid != target
                    && LifecycleDeclarations.supports(BuiltInRegistries.BLOCK.getKey(valid.getBlock()).toString())) {
                var corrected = FabricAdapter.adapt(valid);
                block = valid.getBlock() == target.getBlock() && block.getNbtReference() != null
                        ? corrected.toBaseBlock(block.getNbtReference()) : corrected.toBaseBlock();
            }
            effects = effects.with(SideEffect.VALIDATION, SideEffect.State.OFF);
        }
        var restore = PositionDataRegistry.get(server).prepare(level,
                FabricAdapter.adapt(block.toImmutableState()), PositionDataTransport.positions(delta.after().nbt()));
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
            restore.accept(pos);
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
                var nbt = PositionDataTransport.blockEntity(delta.after().nbt());
                var restore = PositionDataRegistry.get(server).prepare(level, state,
                        PositionDataTransport.positions(delta.after().nbt()));
                chunk.setBlockState(pos, state, Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                if (state.hasBlockEntity()) {
                    var entity = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
                    if (entity == null) {
                        throw new IllegalStateException("Block entity was not created for " + pos);
                    }
                    if (nbt != null) {
                        entity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,
                                level.registryAccess(), nbt));
                        entity.setChanged();
                    }
                }
                restore.accept(pos);
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

    // Whether a block may be installed into a section directly: no mod hook is skipped for it.
    private static boolean directSafe(net.minecraft.world.level.block.state.BlockState state) {
        return PositionDataRegistry.stableWhenEmpty(state)
                && LifecycleDeclarations.directSafe(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    // Workers may only use detached snapshots; even a loaded live read is forbidden.
    private void owner() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Live world access from a preparation worker");
        }
    }
}
