package com.fastasyncworldedit.testharness;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** Registers `faweaudit` only with Ledger installed; every Ledger type lives in LedgerAuditCommands. */
public final class LedgerAuditProbe implements ModInitializer {
    @Override
    public void onInitialize() {
        if (FabricLoader.getInstance().isModLoaded("ledger")) {
            LedgerAuditCommands.register();
        }
    }
}
