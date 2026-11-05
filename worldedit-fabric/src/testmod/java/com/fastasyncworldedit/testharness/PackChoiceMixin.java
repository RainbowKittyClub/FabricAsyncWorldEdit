package com.fastasyncworldedit.testharness;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.multiplayer.ServerData;

/** Answers a server resource pack offer unattended when the harness names a choice. */
@Mixin(ServerData.class)
public abstract class PackChoiceMixin {
    @Inject(method = "getResourcePackStatus", at = @At("HEAD"), cancellable = true)
    private void faweTest$packChoice(CallbackInfoReturnable<ServerData.ServerPackStatus> callback) {
        String choice = System.getProperty("fawe.test.pack");
        if (choice != null) {
            callback.setReturnValue(Boolean.parseBoolean(choice)
                ? ServerData.ServerPackStatus.ENABLED : ServerData.ServerPackStatus.DISABLED);
        }
    }
}
