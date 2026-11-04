package com.fastasyncworldedit.testharness.mixin;

import net.minecraft.world.entity.Entity;

import com.fastasyncworldedit.testharness.S11Writes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Non-shipped S11 oracle: entities removed for any reason. */
@Mixin(Entity.class)
public abstract class EntityRemovalOracle {
    @Inject(method = "remove", at = @At("HEAD"))
    private void worldedit$observeRemoval(Entity.RemovalReason reason, CallbackInfo callback) {
        Entity self = (Entity) (Object) this;
        S11Writes.observe("entity", self.level(), self.getBlockX(), self.getBlockY(), self.getBlockZ());
    }
}
