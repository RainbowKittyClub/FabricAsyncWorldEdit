package com.fastasyncworldedit.fabric.edit;

/** Conservative revision for declared vanilla mutation paths; final read-set comparison remains mandatory. */
public interface ChunkRevision {
    /** Monotonic within the loaded chunk lifetime, including observed ABA and NBT-only updates. */
    public long worldedit$editRevision();

    /** Record a supported mutation; dirtiness from lighting or saving must not call this. */
    public void worldedit$advanceEditRevision();
}
