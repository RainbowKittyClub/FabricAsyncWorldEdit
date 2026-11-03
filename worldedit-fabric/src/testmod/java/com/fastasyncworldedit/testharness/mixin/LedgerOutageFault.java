package com.fastasyncworldedit.testharness.mixin;

import java.util.concurrent.CompletableFuture;

import com.fastasyncworldedit.testharness.LedgerAuditCommands;
import com.github.quiltservertools.ledger.api.BatchAck;
import com.github.quiltservertools.ledger.api.ProducerBatch;
import com.github.quiltservertools.ledger.api.ProducerBatchException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Non-shipped: while `faweaudit dbdown on`, Ledger answers every producer batch as unavailable. */
@Mixin(targets = "com.github.quiltservertools.ledger.api.LedgerApiImpl", remap = false)
public abstract class LedgerOutageFault {
    @Inject(method = "appendBatch", at = @At("HEAD"), cancellable = true)
    private void worldedit$outage(ProducerBatch batch, CallbackInfoReturnable<CompletableFuture<BatchAck>> callback) {
        if (LedgerAuditCommands.databaseDown) {
            callback.setReturnValue(CompletableFuture.failedFuture(new ProducerBatchException(
                    ProducerBatchException.Reason.UNAVAILABLE, "database outage injected by faweaudit dbdown")));
        }
    }
}
