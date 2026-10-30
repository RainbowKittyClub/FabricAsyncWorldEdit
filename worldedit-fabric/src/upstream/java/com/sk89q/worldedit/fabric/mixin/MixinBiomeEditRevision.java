package com.sk89q.worldedit.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.chunk.ChunkAccess;

import com.fastasyncworldedit.fabric.edit.ChunkRevision;

/** Track vanilla bulk biome rewrites, including fillbiome's unchanged-final-value ABA. */
@Mixin(ChunkAccess.class)
public abstract class MixinBiomeEditRevision {
    // ProtoChunk generation is not a live committed-chunk mutation.
    @Inject(method = "fillBiomesFromNoise", at = @At("HEAD"))
    private void worldedit$biomeRevision(CallbackInfo callback) {
        if ((Object) this instanceof ChunkRevision revision) {
            revision.worldedit$advanceEditRevision();
        }
    }
}
