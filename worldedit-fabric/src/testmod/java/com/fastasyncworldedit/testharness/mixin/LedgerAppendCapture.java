package com.fastasyncworldedit.testharness.mixin;

import java.util.concurrent.CompletionStage;

import com.fastasyncworldedit.testharness.LedgerAuditCommands;
import com.github.quiltservertools.ledger.api.BatchAck;
import com.github.quiltservertools.ledger.api.ProducerBatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Non-shipped: keeps the last batch the bridge sent, so the suite can redeliver it unchanged. */
@Mixin(targets = "com.fastasyncworldedit.fabric.compat.ledger.LedgerEditBridge", remap = false)
public abstract class LedgerAppendCapture {
    @Inject(method = "append", at = @At("HEAD"))
    private void worldedit$capture(ProducerBatch batch, long delayMs,
            CallbackInfoReturnable<CompletionStage<BatchAck>> callback) {
        LedgerAuditCommands.lastBatch = batch;
    }
}
