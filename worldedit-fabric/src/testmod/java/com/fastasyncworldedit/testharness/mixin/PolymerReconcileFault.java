package com.fastasyncworldedit.testharness.mixin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fastasyncworldedit.testharness.BridgeFaults;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Non-shipped fault: a bridge that claims reconciliation without asking Polymer to reconcile. */
@Mixin(targets = "com.fastasyncworldedit.fabric.compat.polymer.PolymerEditBridge$PolymerReservation", remap = false)
public abstract class PolymerReconcileFault {
    @Shadow private boolean reconciled;

    @Inject(method = "reconcile", at = @At("HEAD"), cancellable = true)
    private void worldedit$skipReconcile(CallbackInfoReturnable<CompletionStage<Void>> callback) {
        if (BridgeFaults.skipReconcile) {
            this.reconciled = true;
            callback.setReturnValue(CompletableFuture.completedFuture(null));
        }
    }
}
