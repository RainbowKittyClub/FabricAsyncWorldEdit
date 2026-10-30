package com.fastasyncworldedit.core.queue;

import java.util.concurrent.Future;

/** Opt-in adapter boundary for receipt-based publication and awaited finalization. */
public interface CoordinatedChunkGet extends IChunkGet {

    /**
     * Submit the detached set. The adapter owns preprocessing, actual-delta postprocessing,
     * and finalization; it must register and return a non-null future before returning.
     * Neither submission nor completion may block the world's owner thread.
     */
    public <T extends Future<T>> T submitCommit(
            IQueueExtent<?> owner, IChunk chunk, IChunkSet set, Runnable finalizer);

}
