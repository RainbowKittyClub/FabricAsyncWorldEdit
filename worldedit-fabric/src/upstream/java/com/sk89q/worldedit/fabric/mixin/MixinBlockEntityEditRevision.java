package com.sk89q.worldedit.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import com.fastasyncworldedit.fabric.edit.ChunkRevision;

/** Track standard block-entity dirty notifications without loading an absent chunk. */
@Mixin(Level.class)
public abstract class MixinBlockEntityEditRevision {
    // Inventory/setChanged writes use this method even when their chunk was already dirty.
    @Inject(method = "blockEntityChanged", at = @At("HEAD"))
    private void worldedit$blockEntityChanged(BlockPos pos, CallbackInfo callback) {
        if ((Object) this instanceof ServerLevel level) {
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk != null) {
                ((ChunkRevision) chunk).worldedit$advanceEditRevision();
            }
        }
    }
}
