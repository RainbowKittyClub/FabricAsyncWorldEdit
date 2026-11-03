package com.fastasyncworldedit.testharness.mixin;

import net.minecraft.server.level.ServerLevel;

import com.fastasyncworldedit.fabric.edit.EditDelta;
import com.fastasyncworldedit.fabric.edit.EditValue;
import com.fastasyncworldedit.fabric.edit.FabricWorldAccess;
import com.fastasyncworldedit.testharness.CommitContractProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Non-shipped fault: the written cell cannot be read back, so its actual value is unknown. */
@Mixin(FabricWorldAccess.class)
public abstract class ReadBackFault {
    @Inject(method = "read(Lnet/minecraft/server/level/ServerLevel;Lcom/fastasyncworldedit/fabric/edit/EditDelta;)"
            + "Lcom/fastasyncworldedit/fabric/edit/EditValue;", at = @At("HEAD"), remap = false)
    private void worldedit$failReadBack(ServerLevel level, EditDelta delta, CallbackInfoReturnable<EditValue> callback) {
        if (CommitContractProbe.failReadBack(level, delta)) {
            throw new IllegalStateException("S7 injected read-back failure");
        }
    }
}
