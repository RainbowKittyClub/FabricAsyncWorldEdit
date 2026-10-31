package com.fastasyncworldedit.fabric.compat;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.fastasyncworldedit.fabric.edit.EditActorContext;
import com.fastasyncworldedit.fabric.edit.EditDelta;

/** Optional native protection gate; installed-but-broken OPAC never becomes an absent provider. */
public final class NativeClaims {
    private NativeClaims() {
    }

    /** Whether the installed claim authority requires guarded operations. */
    public static boolean required() {
        return FabricLoader.getInstance().isModLoaded("openpartiesandclaims");
    }

    /** Authorize on the owner thread, in the same turn as the corresponding write. */
    public static boolean authorize(ServerLevel level, EditActorContext context, EditDelta delta) {
        owner(level);
        return !required() || Provider.INSTANCE.authorizeDirect(level, player(level, context), context, delta);
    }

    /** Refuse sensitive data before serialization or exposure to a callback/clipboard. */
    public static void snapshot(ServerLevel level, EditActorContext context, int chunkX, int chunkZ) {
        owner(level);
        if (required() && !Provider.INSTANCE.authorizeSnapshot(level, player(level, context), context, chunkX, chunkZ)) {
            throw new RefusedRead();
        }
    }

    /** Explicit refusal for operations without a safe footprint/dependency contract. */
    public static void unsupported(String operation) {
        if (required()) {
            throw new com.fastasyncworldedit.core.internal.exception.FaweException(
                    "OPAC protected editing refuses unsupported operation: " + operation);
        }
    }

    private static ServerPlayer player(ServerLevel level, EditActorContext context) {
        return context == null ? null : level.getServer().getPlayerList().getPlayer(context.actorId());
    }

    private static void owner(ServerLevel level) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Native protection requires the server thread");
        }
    }

    /** A denied sensitive read is not an empty inventory and cannot be used as clipboard content. */
    public static final class RefusedRead extends com.fastasyncworldedit.core.internal.exception.FaweException {
        public RefusedRead() {
            super("OPAC protected data read refused");
        }
    }

    // Lazy loading avoids resolving OPAC classes when the optional mod is absent.
    private static final class Provider {
        private static final EditIntegration INSTANCE = load();

        private static EditIntegration load() {
            try {
                return (EditIntegration) Class.forName(
                        "com.fastasyncworldedit.fabric.compat.opac.OpacAuthorizer").getConstructor().newInstance();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Installed OPAC integration failed initialization", failure);
            }
        }
    }
}
