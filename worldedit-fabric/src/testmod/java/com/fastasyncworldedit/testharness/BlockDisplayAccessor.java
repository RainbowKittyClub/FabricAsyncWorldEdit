package com.fastasyncworldedit.testharness;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;

/** The block a client-side block display shows; the getter is private. */
@Mixin(Display.BlockDisplay.class)
public interface BlockDisplayAccessor {
    @Invoker("getBlockState")
    BlockState faweTest$blockState();
}
