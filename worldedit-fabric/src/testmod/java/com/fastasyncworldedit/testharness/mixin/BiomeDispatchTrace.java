package com.fastasyncworldedit.testharness.mixin;

import java.util.ArrayList;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.biome.BiomeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Non-shipped trace of the actual WNA biome command's adapter chain. */
@Mixin(EditSession.class)
public abstract class BiomeDispatchTrace {
    @Inject(method = "setBiome(Lcom/sk89q/worldedit/math/BlockVector3;Lcom/sk89q/worldedit/world/biome/BiomeType;)Z",
            at = @At("HEAD"), remap = false)
    private void worldedit$biomePath(BlockVector3 pos, BiomeType biome, CallbackInfoReturnable<Boolean> callback) {
        if (pos.x() != 1105 || pos.y() != 112 || pos.z() != 1041) return;
        Extent extent = ((EditSession) (Object) this).getExtent();
        var path = new ArrayList<String>();
        for (int i = 0; i < 20; i++) {
            path.add(extent.getClass().getName() + "=" + extent.getBiome(pos).id());
            if (!(extent instanceof AbstractDelegateExtent delegate)) break;
            extent = delegate.getExtent();
        }
        System.out.println("FAWE_S5_BIOME_TRACE " + biome.id() + " " + path);
    }

    @Inject(method = "setBiome(Lcom/sk89q/worldedit/math/BlockVector3;Lcom/sk89q/worldedit/world/biome/BiomeType;)Z",
            at = @At("RETURN"), remap = false)
    private void worldedit$biomeResult(BlockVector3 pos, BiomeType biome, CallbackInfoReturnable<Boolean> callback) {
        if (pos.x() == 1105 && pos.y() == 112 && pos.z() == 1041) {
            System.out.println("FAWE_S5_BIOME_TRACE result=" + callback.getReturnValue());
        }
    }
}
