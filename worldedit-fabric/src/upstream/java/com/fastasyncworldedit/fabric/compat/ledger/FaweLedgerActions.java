package com.fastasyncworldedit.fabric.compat.ledger;

import com.github.quiltservertools.ledger.actions.AbstractActionType;
import com.github.quiltservertools.ledger.registry.ActionRegistry;

/**
 * Ledger action types for FAWE deltas. Rows carry both sides in versioned extra data
 * ({@link LedgerPayloads}); rollback and restore stay unsupported until FAWE's replay service.
 */
final class FaweLedgerActions {
    static final String BLOCK = "fawe-block";
    static final String BIOME = "fawe-biome";

    private FaweLedgerActions() {
    }

    /** Idempotent: Ledger keeps the first supplier per identifier. */
    static void register() {
        ActionRegistry.INSTANCE.registerActionType(Block::new);
        ActionRegistry.INSTANCE.registerActionType(Biome::new);
    }

    static final class Block extends AbstractActionType {
        @Override
        public String getIdentifier() {
            return BLOCK;
        }

        @Override
        public String getTranslationType() {
            return "block";
        }
    }

    static final class Biome extends AbstractActionType {
        @Override
        public String getIdentifier() {
            return BIOME;
        }

        @Override
        public String getTranslationType() {
            return "biome";
        }
    }
}
