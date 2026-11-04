package com.fastasyncworldedit.fabric.edit;

/** A unit refused before writing because its operator disconnected or lost the authority for it. */
public final class OperatorRefusedException extends IllegalStateException {
    public OperatorRefusedException() {
        super("Current operator is disconnected or command authority is unavailable");
    }
}
