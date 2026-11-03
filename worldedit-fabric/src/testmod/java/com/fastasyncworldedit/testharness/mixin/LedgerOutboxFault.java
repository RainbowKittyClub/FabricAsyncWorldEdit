package com.fastasyncworldedit.testharness.mixin;

import java.io.IOException;
import java.nio.file.Path;

import com.fastasyncworldedit.testharness.LedgerAuditCommands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Non-shipped: while `faweaudit diskfull on`, every outbox write fails as a full disk would. */
@Mixin(targets = "com.fastasyncworldedit.fabric.compat.ledger.LedgerOutbox", remap = false)
public abstract class LedgerOutboxFault {
    @Inject(method = "write", at = @At("HEAD"))
    private void worldedit$diskFull(Path target, byte[] data, CallbackInfo callback) throws IOException {
        if (LedgerAuditCommands.diskFull) {
            throw new IOException("No space left on device (injected for " + target.getFileName() + ")");
        }
    }
}
