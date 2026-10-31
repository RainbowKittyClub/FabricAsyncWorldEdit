package com.sk89q.worldedit.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;

/** Reuse vanilla's per-recipient construction point after required publication maintenance. */
@Mixin(PlayerChunkSender.class)
public interface InvokerPlayerChunkSender {
    /** Invoke normal chunk construction separately for each actual watcher. */
    @Invoker("sendChunk")
    static void worldedit$sendChunk(ServerGamePacketListenerImpl connection, ServerLevel level, LevelChunk chunk) {
        throw new AssertionError("Mixin did not apply");
    }
}
