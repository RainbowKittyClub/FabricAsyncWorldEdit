package com.fastasyncworldedit.s6.mixin;

import com.fastasyncworldedit.s6.ClaimsProbe;

import com.fastasyncworldedit.fabric.compat.opac.OpacAuthorizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only installed-provider initialization failure, never shipped with the candidate. */
@Mixin(value = OpacAuthorizer.class, remap = false)
public abstract class BrokenOpacMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void worldedit$s6BrokenProvider(CallbackInfo ci) {
        if (ClaimsProbe.brokenProvider) {
            throw new IllegalStateException("s6-installed-provider-init-failure");
        }
    }
}
