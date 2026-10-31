package com.fastasyncworldedit.fabric.edit;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LightEngine;

/** Owner-thread section installation for explicitly validated, non-block-entity deltas. */
final class FabricSectionPublication {
    private FabricSectionPublication() { }

    // Copy current unchanged cells, not the worker's potentially stale whole-section palette.
    static void install(ServerLevel level, LevelChunk chunk, List<EditDelta> deltas,
            FabricStateMapping mapping) {
        int sectionIndex = chunk.getSectionIndex(deltas.getFirst().y());
        var previous = chunk.getSection(sectionIndex);
        var replacement = previous.copy();
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
        if (previous.hasOnlyAir() != replacement.hasOnlyAir()) {
            int sectionY = deltas.getFirst().y() >> 4;
            level.getLightEngine().updateSectionStatus(
                    SectionPos.of(chunk.getPos().x(), sectionY, chunk.getPos().z()), replacement.hasOnlyAir());
            level.getChunkSource().onSectionEmptinessChanged(
                    chunk.getPos().x(), sectionY, chunk.getPos().z(), replacement.hasOnlyAir());
        }
        for (EditDelta delta : deltas) {
            int x = delta.x() & 15;
            int z = delta.z() & 15;
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
                chunk.getSkyLightSources().update(chunk, x, delta.y(), z);
                level.getLightEngine().checkBlock(new BlockPos(delta.x(), delta.y(), delta.z()));
            }
        }
    }
}
