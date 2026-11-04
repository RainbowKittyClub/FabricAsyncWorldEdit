package com.fastasyncworldedit.testharness.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import com.fastasyncworldedit.testharness.S11Writes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Non-shipped S11 oracle: every block and block-entity write to a live chunk. */
@Mixin(LevelChunk.class)
public abstract class ChunkWriteOracle {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void worldedit$observeBlock(BlockPos pos, BlockState state, int flags,
            CallbackInfoReturnable<BlockState> callback) {
        S11Writes.observe("block", ((LevelChunk) (Object) this).getLevel(), pos.getX(), pos.getY(), pos.getZ());
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"))
    private void worldedit$observeBlockEntity(BlockEntity blockEntity, CallbackInfo callback) {
        BlockPos pos = blockEntity.getBlockPos();
        S11Writes.observe("block-entity", ((LevelChunk) (Object) this).getLevel(), pos.getX(), pos.getY(), pos.getZ());
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void worldedit$observeBlockEntityRemoval(BlockPos pos, CallbackInfo callback) {
        S11Writes.observe("block-entity", ((LevelChunk) (Object) this).getLevel(), pos.getX(), pos.getY(), pos.getZ());
    }
}
