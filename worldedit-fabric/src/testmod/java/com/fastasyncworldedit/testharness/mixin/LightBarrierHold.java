package com.fastasyncworldedit.testharness.mixin;

import java.util.concurrent.CompletableFuture;

import com.fastasyncworldedit.testharness.OptimizationProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;

/** Non-shipped: while `fawes9 hold on`, every light barrier also waits for the probe's gate. */
@Mixin(targets = "com.fastasyncworldedit.fabric.edit.FabricOptimizations", remap = false)
public abstract class LightBarrierHold {
    @Inject(method = "relightBarrier", at = @At("RETURN"), cancellable = true)
    private void worldedit$hold(ServerLevel level, int chunkX, int chunkZ,
            CallbackInfoReturnable<CompletableFuture<?>> callback) {
        CompletableFuture<Void> gate = OptimizationProbe.lightGate;
        if (gate != null) {
            callback.setReturnValue(callback.getReturnValue().thenCompose(unused -> gate));
        }
    }
}
