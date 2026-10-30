package com.fastasyncworldedit.fabric.edit;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sk89q.worldedit.util.SideEffectSet;

/**
 * Explicit authority and provenance carried across every asynchronous boundary.
 *
 * @param actorId current operator, never the historical actor of a replay
 * @param actorName display name of the current operator
 * @param source operation entry point
 * @param operationId unique operation identity
 * @param parentId original operation for undo/replay, or null
 * @param originalActorId historical provenance only, or null
 * @param dimension dimension identifier
 * @param sideEffects requested vanilla side effects
 * @param cancellation shared, monotonic cancellation signal
 */
public record EditActorContext(UUID actorId, String actorName, Source source, UUID operationId,
        UUID parentId, UUID originalActorId, String dimension, SideEffectSet sideEffects,
        Cancellation cancellation) {

    public EditActorContext {
        Objects.requireNonNull(actorId, "A current actor is required");
        Objects.requireNonNull(actorName);
        Objects.requireNonNull(source);
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(dimension);
        Objects.requireNonNull(sideEffects);
        var effects = new java.util.EnumMap<com.sk89q.worldedit.util.SideEffect,
                com.sk89q.worldedit.util.SideEffect.State>(com.sk89q.worldedit.util.SideEffect.class);
        for (var effect : com.sk89q.worldedit.util.SideEffect.values()) {
            effects.put(effect, sideEffects.getState(effect));
        }
        sideEffects = new SideEffectSet(effects);
        Objects.requireNonNull(cancellation);
        if (actorName.isBlank() || dimension.isBlank()) {
            throw new IllegalArgumentException("Actor name and dimension must not be blank");
        }
    }

    /** Identifies authority-bearing entry points without conflating replay provenance. */
    public enum Source {
        PLAYER, API, UNDO, REDO, LEDGER_ROLLBACK, LEDGER_RESTORE, ADMINISTRATION
    }

    /** Cancellation cannot be cleared or used to retract already published changes. */
    public static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        /** Cancel remaining work; already published receipts remain valid. */
        public void cancel() {
            cancelled.set(true);
        }

        /** Whether remaining work must be refused. */
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}
