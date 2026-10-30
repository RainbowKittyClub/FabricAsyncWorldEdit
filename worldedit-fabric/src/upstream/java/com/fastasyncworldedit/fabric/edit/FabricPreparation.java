package com.fastasyncworldedit.fabric.edit;

import com.fastasyncworldedit.core.extent.filter.block.CharFilterBlock;
import com.fastasyncworldedit.core.queue.Filter;
import com.fastasyncworldedit.core.queue.implementation.SingleThreadQueueExtent;
import com.fastasyncworldedit.core.queue.implementation.chunk.ChunkHolder;
import com.sk89q.worldedit.regions.Region;

/** Runs upstream filters on the admitted core queue; conversion stays at FabricBufferEdits.freeze. */
public final class FabricPreparation {
    private FabricPreparation() { }

    /** Apply one chunk's region through the normal ChunkHolder filter and completion path. */
    public static void apply(SingleThreadQueueExtent queue, int chunkX, int chunkZ,
            Region region, Filter filter) {
        ChunkHolder<?> holder = (ChunkHolder<?>) queue.getOrCreateChunk(chunkX, chunkZ);
        holder.filterBlocks(filter, new CharFilterBlock(queue), region, true);
        filter.finish();
    }
}
