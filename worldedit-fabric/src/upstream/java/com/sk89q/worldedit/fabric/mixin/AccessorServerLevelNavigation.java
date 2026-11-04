package com.sk89q.worldedit.fabric.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

@Mixin(ServerLevel.class)
public interface AccessorServerLevelNavigation {
    /** Mobs whose paths vanilla's sendBlockUpdated rechecks when a collision shape changes. */
    @Accessor("navigatingMobs")
    Set<Mob> worldedit$navigatingMobs();
}
