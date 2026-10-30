package com.sk89q.worldedit.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import com.fastasyncworldedit.fabric.edit.ChunkRevision;

/** Observe supported mutations; lighting/save dirtiness is not a block mutation. */
@Mixin(LevelChunk.class)
public abstract class MixinChunkEditRevision implements ChunkRevision {
    @Unique
    private volatile long worldedit$editRevision;

    @Override
    public long worldedit$editRevision() {
        return worldedit$editRevision;
    }

    @Override
    public synchronized void worldedit$advanceEditRevision() {
        worldedit$editRevision++;
    }

    // Record ABA even when the final state matches an earlier snapshot.
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void worldedit$blockRevision(BlockPos pos, BlockState state, int flags,
            CallbackInfoReturnable<BlockState> callback) {
        if (((LevelChunk) (Object) this).getBlockState(pos) != state) {
            worldedit$advanceEditRevision();
        }
    }

    // Block entity replacement can occur without changing the block state.
    @Inject(method = {"setBlockEntity", "removeBlockEntity"}, at = @At("HEAD"))
    private void worldedit$blockEntityRevision(CallbackInfo callback) {
        worldedit$advanceEditRevision();
    }
}
