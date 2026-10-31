package com.fastasyncworldedit.testharness.mixin;

import com.fastasyncworldedit.fabric.edit.FabricWorldAccess;
import com.fastasyncworldedit.testharness.PublicationProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Non-shipped fault after the native mutation, before its caller captures the receipt. */
@Mixin(FabricWorldAccess.class)
public abstract class PublicationFault {
    @Inject(method = "write", at = @At("TAIL"), remap = false)
    private void worldedit$afterFallback(CallbackInfo callback) {
        PublicationProbe.afterFallbackWrite();
    }
}
