package com.fastasyncworldedit.testharness.mixin;

import com.fastasyncworldedit.testharness.PreparationProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Test-only tripwire for live reads from the production preparation executor. */
@Mixin(Level.class)
public abstract class PreparationReadTrap {
    @Inject(method = {"getBlockState", "getBlockEntity"}, at = @At("HEAD"))
    private void faweTest$read(BlockPos position, CallbackInfoReturnable<?> callback) {
        PreparationProbe.checkLiveRead();
    }
}
