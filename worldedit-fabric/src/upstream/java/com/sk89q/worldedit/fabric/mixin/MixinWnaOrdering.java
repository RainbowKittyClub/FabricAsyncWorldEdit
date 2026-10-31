package com.sk89q.worldedit.fabric.mixin;

import com.fastasyncworldedit.core.wrappers.WorldWrapper;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.fabric.FabricWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep Fabric WNA ordering out of FAWE's non-publishing placeholder queue. */
@Mixin(EditSession.class)
public abstract class MixinWnaOrdering {
    @Shadow @Final private boolean wnaMode;

    // Reorder-mode initialization otherwise buffers biome writes forever in ForgetfulExtentBuffer.
    @Inject(method = {"setReorderMode", "enableStandardMode"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void worldedit$nativeOrdering(CallbackInfo callback) {
        if (wnaMode && WorldWrapper.unwrap(((EditSession) (Object) this).getWorld()) instanceof FabricWorld) {
            callback.cancel();
        }
    }
}
