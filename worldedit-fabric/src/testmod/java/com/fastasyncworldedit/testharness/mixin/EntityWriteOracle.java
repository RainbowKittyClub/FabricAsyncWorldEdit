package com.fastasyncworldedit.testharness.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import com.fastasyncworldedit.testharness.S11Writes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Non-shipped S11 oracle: entities added to a server level. */
@Mixin(ServerLevel.class)
public abstract class EntityWriteOracle {
    @Inject(method = "addFreshEntity", at = @At("HEAD"))
    private void worldedit$observeEntity(Entity entity, CallbackInfoReturnable<Boolean> callback) {
        S11Writes.observe("entity", (ServerLevel) (Object) this, entity.getBlockX(), entity.getBlockY(), entity.getBlockZ());
    }
}
