package com.fastasyncworldedit.fabric.edit;

import java.util.ArrayList;
import java.util.List;

import com.sk89q.worldedit.fabric.mixin.AccessorServerLevelNavigation;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** Owner-thread section installation for explicitly validated, non-block-entity deltas. */
final class FabricSectionPublication {
    private FabricSectionPublication() { }

    // Copy current unchanged cells, not the worker's potentially stale whole-section palette.
    static void install(ServerLevel level, LevelChunk chunk, List<EditDelta> deltas,
            FabricStateMapping mapping, FabricOptimizations optimizations, SideEffectSet effects) {
        int sectionIndex = chunk.getSectionIndex(deltas.getFirst().y());
        int sectionY = deltas.getFirst().y() >> 4;
        SectionPos sectionPos = SectionPos.of(chunk.getPos().x(), sectionY, chunk.getPos().z());
        var previous = chunk.getSection(sectionIndex);
        // This constructor recounts, which is where vanilla and Lithium build per-section counters;
        // copy() skips that, and Lithium's random-tick data then fails on the next flag change.
        var replacement = new LevelChunkSection(previous.getStates().copy(), previous.getBiomes().copy());
        for (EditDelta delta : deltas) {
            if (chunk.getSectionIndex(delta.y()) != sectionIndex) {
                throw new IllegalArgumentException("Section publication crossed a section boundary");
            }
            replacement.setBlockState(delta.x() & 15, delta.y() & 15, delta.z() & 15,
                    mapping.nativeState(delta.after().id()));
        }
        chunk.getSections()[sectionIndex] = replacement;
        ((ChunkRevision) chunk).worldedit$advanceEditRevision();
        chunk.markUnsaved();
        optimizations.sectionReplaced(previous, sectionPos);
        if (previous.hasOnlyAir() != replacement.hasOnlyAir()) {
            level.getLightEngine().updateSectionStatus(sectionPos, replacement.hasOnlyAir());
            level.getChunkSource().onSectionEmptinessChanged(
                    chunk.getPos().x(), sectionY, chunk.getPos().z(), replacement.hasOnlyAir());
        }
        List<BlockPos> reshaped = new ArrayList<>();
        for (EditDelta delta : deltas) {
            int x = delta.x() & 15;
            int z = delta.z() & 15;
            BlockPos pos = new BlockPos(delta.x(), delta.y(), delta.z());
            var before = mapping.nativeState(delta.before().id());
            var after = mapping.nativeState(delta.after().id());
            for (var entry : chunk.getHeightmaps()) {
                Heightmap.Types type = entry.getKey();
                if (type == Heightmap.Types.MOTION_BLOCKING || type == Heightmap.Types.MOTION_BLOCKING_NO_LEAVES
                        || type == Heightmap.Types.OCEAN_FLOOR || type == Heightmap.Types.WORLD_SURFACE) {
                    entry.getValue().update(x, delta.y(), z, after);
                }
            }
            if (LightEngine.hasDifferentLightProperties(before, after)) {
                // ScalableLux drops vanilla's sky sources (null) and computes sky light itself.
                var skySources = chunk.getSkyLightSources();
                if (skySources != null) skySources.update(chunk, x, delta.y(), z);
                level.getLightEngine().checkBlock(pos);
            }
            // POIs and mob paths follow the declared side effects, as WorldEdit's native path does. The
            // path-type cache is invalidated regardless: a stale entry is wrong, not a chosen effect.
            if (effects.shouldApply(SideEffect.POI_UPDATE)) {
                level.updatePOIOnBlockStateChange(pos, before, after);
            }
            level.getPathTypeCache().invalidate(pos);
            if (effects.shouldApply(SideEffect.ENTITY_AI) && Shapes.joinIsNotEmpty(before.getCollisionShape(level, pos),
                    after.getCollisionShape(level, pos), BooleanOp.NOT_SAME)) {
                reshaped.add(pos);
            }
        }
        recomputePaths(level, reshaped);
    }

    // As sendBlockUpdated: collect first, since a recompute may change the navigating set.
    private static void recomputePaths(ServerLevel level, List<BlockPos> reshaped) {
        if (reshaped.isEmpty()) {
            return;
        }
        List<PathNavigation> stale = new ArrayList<>();
        for (Mob mob : ((AccessorServerLevelNavigation) level).worldedit$navigatingMobs()) {
            PathNavigation navigation = mob.getNavigation();
            for (BlockPos pos : reshaped) {
                if (navigation.shouldRecomputePath(pos)) {
                    stale.add(navigation);
                    break;
                }
            }
        }
        stale.forEach(PathNavigation::recomputePath);
    }
}
