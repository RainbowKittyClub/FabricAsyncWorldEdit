package com.fastasyncworldedit.testharness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;

/** File-controlled native renderer probe; never included in the production jar. */
@Mixin(Minecraft.class)
public abstract class NativeDriverMixin {
    @Unique private String faweTest$lastId = "";
    @Unique private String faweTest$lastState = "";

    // Observe completed real frames and execute commands through the real client connection.
    @Inject(method = "runTick", at = @At("TAIL"))
    private void faweTest$frame(boolean advanceGameTime, CallbackInfo ci) throws IOException {
        Minecraft client = (Minecraft) (Object) this;
        String state = client.gui.overlay() != null ? "loading"
            : client.level != null && client.player != null && client.gui.screen() == null ? "world"
            : client.gui.screen() instanceof TitleScreen ? "title" : "screen";
        if (!state.equals(faweTest$lastState)) {
            faweTest$lastState = state;
            JsonObject event = new JsonObject();
            event.addProperty("state", state);
            faweTest$event(event);
        }
        Path requestFile = Path.of(System.getProperty("fawe.test.request"));
        if (!Files.isRegularFile(requestFile)) {
            return;
        }
        JsonObject request = JsonParser.parseString(Files.readString(requestFile)).getAsJsonObject();
        String id = request.get("id").getAsString();
        if (id.equals(faweTest$lastId)) {
            return;
        }
        faweTest$lastId = id;
        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        switch (request.get("action").getAsString()) {
            case "command" -> {
                if (!state.equals("world")) {
                    throw new IllegalStateException("Command requested without a ready world");
                }
                client.getConnection().sendCommand(request.get("command").getAsString());
                result.addProperty("sent", true);
                faweTest$event(result);
            }
            case "command-tree" -> {
                result.addProperty("visible", client.getConnection().getCommands().getRoot()
                    .getChild(request.get("command").getAsString()) != null);
                faweTest$event(result);
            }
            case "capture" -> {
                if (!state.equals("world") && !state.equals("title")) {
                    throw new IllegalStateException("Capture requested before a rendered scene");
                }
                Path imageFile = Path.of(System.getProperty("fawe.test.frames")).resolve(id + ".png");
                Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
                    try (image) {
                        int first = image.getPixel(0, 0);
                        long different = 0;
                        for (int y = 0; y < image.getHeight(); y++) {
                            for (int x = 0; x < image.getWidth(); x++) {
                                if (image.getPixel(x, y) != first) {
                                    different++;
                                }
                            }
                        }
                        image.writeToFile(imageFile);
                        result.addProperty("width", image.getWidth());
                        result.addProperty("height", image.getHeight());
                        result.addProperty("differentPixels", different);
                        result.addProperty("image", imageFile.toString());
                        result.addProperty("state", state);
                        faweTest$event(result);
                    } catch (IOException failure) {
                        throw new IllegalStateException("Native capture failed", failure);
                    }
                });
            }
            case "stop" -> client.stop();
            default -> throw new IllegalArgumentException("Unknown native request " + request);
        }
    }

    // Each line is a complete typed observation, not a successful resource-pack reply.
    @Unique
    private static synchronized void faweTest$event(JsonObject event) throws IOException {
        Files.writeString(Path.of(System.getProperty("fawe.test.events")), event + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
