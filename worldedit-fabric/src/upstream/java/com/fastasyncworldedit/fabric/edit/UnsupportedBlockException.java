package com.fastasyncworldedit.fabric.edit;

/**
 * A cell whose before or after block has no lifecycle capability yet. It is refused before any write;
 * WorldEdit sessions skip such cells and report them once the edit is flushed.
 */
public final class UnsupportedBlockException extends UnsupportedOperationException {
    private final String block;
    private final int x;
    private final int y;
    private final int z;

    public UnsupportedBlockException(String block, int x, int y, int z) {
        super("No lifecycle capability for " + block + " at " + x + "," + y + "," + z);
        this.block = block;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public String block() {
        return block;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }
}
