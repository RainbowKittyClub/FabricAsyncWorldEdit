package com.sk89q.worldedit.world;

import com.fastasyncworldedit.core.history.changeset.AbstractChangeSet;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.Extent;

import javax.annotation.Nullable;

/** Opt-in native world whose authority belongs to an edit session, not a worker thread. */
public interface ActorBoundWorld extends World {
    /** Return a new session view; a missing actor must not inherit the previous view's authority. */
    World bindActor(@Nullable Actor actor);

    /**
     * Bind the actor and the session's side effects; writes arriving with WorldEdit's defaults use
     * them instead, since a WNA-mode session passes no side effects of its own.
     */
    default World bindActor(@Nullable Actor actor, com.sk89q.worldedit.util.SideEffectSet sideEffects) {
        return bindActor(actor);
    }

    /** Bind replay provenance without replacing the current operator's authority. */
    default World bindReplay(@Nullable Actor actor, com.sk89q.worldedit.history.changeset.ChangeSet history,
            boolean redo) {
        throw new UnsupportedOperationException("This platform has no replay provenance binding");
    }

    /** Wrap native history so authorization, mutation and actual-delta capture share one owner turn. */
    Extent withCommittedHistory(Extent extent, AbstractChangeSet history);

    /** Refuse operations whose compound dependencies the native backend cannot preserve. */
    void checkOperation(String operation);
}
