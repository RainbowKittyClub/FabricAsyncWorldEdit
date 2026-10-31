package com.sk89q.worldedit.fabric.mixin;

import com.fastasyncworldedit.fabric.compat.NativeClaims;
import com.sk89q.worldedit.command.ClipboardCommands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Refuse destructive clipboard commands before replacing the clipboard or touching their source. */
@Mixin(ClipboardCommands.class)
public abstract class MixinClipboardProtection {
    @Inject(method = "cut", at = @At("HEAD"), remap = false)
    private void worldedit$refuseCut(CallbackInfo callback) {
        NativeClaims.unsupported("cut");
    }
}
