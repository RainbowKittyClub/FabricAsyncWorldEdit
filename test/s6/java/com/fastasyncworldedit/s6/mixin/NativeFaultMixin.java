package com.fastasyncworldedit.s6.mixin;

import com.fastasyncworldedit.s6.ClaimsProbe;

import com.fastasyncworldedit.fabric.edit.EditDelta;
import com.fastasyncworldedit.fabric.edit.FabricWorldAccess;
import com.sk89q.worldedit.util.SideEffectSet;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only fault after the real WNA writer has changed the world. */
@Mixin(value = FabricWorldAccess.class, remap = false)
public abstract class NativeFaultMixin {
    @Inject(method = "writeNative", at = @At("RETURN"))
    private void worldedit$s6AfterMutation(ServerLevel level, EditDelta delta, SideEffectSet effects,
            CallbackInfo ci) {
        if (ClaimsProbe.mutationFault && delta.x() == 3232 && delta.y() == 80 && delta.z() == 3200) {
            ClaimsProbe.mutationCalls++;
            throw new IllegalStateException("s6-native-after-mutation");
        }
    }
}
