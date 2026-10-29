/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.fabric.mixin;

import com.sk89q.worldedit.fabric.internal.ExtendedChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

import javax.annotation.Nullable;

@Mixin(LevelChunk.class)
public abstract class MixinLevelChunkSetBlockHook implements ExtendedChunk {
    @Nullable
    @Override
    public BlockState setBlockState(BlockPos pos, BlockState state, int flags, boolean update) {
        // Vanilla now provides the side-effect switch; no redirect or mutable chunk flag is needed.
        return ((LevelChunk) (Object) this).setBlockState(pos, state,
            update ? flags : flags | Block.UPDATE_SKIP_ON_PLACE);
    }
}
