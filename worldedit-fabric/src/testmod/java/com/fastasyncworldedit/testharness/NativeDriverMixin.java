package com.fastasyncworldedit.testharness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.fastasyncworldedit.fixture.PaletteFixture;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;

/** File-controlled native renderer probe; never included in the production jar. */
@Mixin(Minecraft.class)
public abstract class NativeDriverMixin {
    @Unique private String faweTest$lastId = "";
    @Unique private String faweTest$lastState = "";

    // Observe completed real frames and execute commands through the real client connection.
    @Inject(method = "runTick", at = @At("TAIL"))
    private void faweTest$frame(boolean advanceGameTime, CallbackInfo ci) throws IOException {
        Minecraft client = (Minecraft) (Object) this;
        String state = client.gui.screen() instanceof DisconnectedScreen ? "disconnected"
            : client.gui.overlay() != null ? "loading"
            : client.level != null && client.player != null && client.gui.screen() == null ? "world"
            : client.gui.screen() instanceof TitleScreen ? "title" : "screen";
        if (!state.equals(faweTest$lastState)) {
            faweTest$lastState = state;
            JsonObject event = new JsonObject();
            event.addProperty("state", state);
            if (client.gui.screen() instanceof DisconnectedScreen screen) {
                event.addProperty("reason", screen.getNarrationMessage().getString());
            }
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
            case "palette-registry" -> {
                result.add("registry", new Gson().toJsonTree(PaletteFixture.describe()));
                faweTest$event(result);
            }
            case "command" -> {
                if (!state.equals("world")) {
                    throw new IllegalStateException("Command requested without a ready world");
                }
                client.getConnection().sendCommand(request.get("command").getAsString());
                result.addProperty("sent", true);
                faweTest$event(result);
            }
            // A right-click with the main-hand item, as the player aims (the server sets the aim by tp).
            case "use" -> {
                if (!state.equals("world")) {
                    throw new IllegalStateException("Use requested without a ready world");
                }
                result.addProperty("result", client.gameMode.useItem(client.player,
                        net.minecraft.world.InteractionHand.MAIN_HAND).toString());
                faweTest$event(result);
            }
            // A left-click on the aimed block, then released before any break progress.
            case "attack" -> {
                if (!state.equals("world")) {
                    throw new IllegalStateException("Attack requested without a ready world");
                }
                if (!(client.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)
                        || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    throw new IllegalStateException("Attack requested without a block in aim");
                }
                result.addProperty("result", client.gameMode.startDestroyBlock(hit.getBlockPos(), hit.getDirection()));
                client.gameMode.stopDestroyBlock();
                result.addProperty("pos", hit.getBlockPos().toShortString());
                faweTest$event(result);
            }
            // What this client holds in each named box: cells, block light, block entities and
            // displays, at positions relative to the box minimum so two boxes compare directly.
            case "region" -> {
                result.addProperty("inWorld", client.level != null && client.player != null);
                if (client.level != null) {
                    JsonObject boxes = new JsonObject();
                    for (var element : request.getAsJsonArray("boxes")) {
                        JsonObject box = element.getAsJsonObject();
                        boxes.add(box.get("name").getAsString(), faweTest$region(client.level, box));
                    }
                    result.add("boxes", boxes);
                }
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

    @Unique
    private static JsonObject faweTest$region(ClientLevel level, JsonObject box) {
        int x1 = box.get("x1").getAsInt(), y1 = box.get("y1").getAsInt(), z1 = box.get("z1").getAsInt();
        int x2 = box.get("x2").getAsInt(), y2 = box.get("y2").getAsInt(), z2 = box.get("z2").getAsInt();
        JsonArray cells = new JsonArray();
        boolean loaded = true;
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    loaded &= level.hasChunkAt(pos);
                    JsonObject cell = new JsonObject();
                    cell.addProperty("at", (x - x1) + "," + (y - y1) + "," + (z - z1));
                    cell.addProperty("state", BlockStateParser.serialize(level.getBlockState(pos)));
                    cell.addProperty("light", level.getBrightness(LightLayer.BLOCK, pos));
                    cell.addProperty("lightAbove", level.getBrightness(LightLayer.BLOCK, pos.above()));
                    var blockEntity = level.getBlockEntity(pos);
                    if (blockEntity != null) {
                        cell.addProperty("blockEntity", blockEntity.saveWithoutMetadata(level.registryAccess()).toString());
                    }
                    cells.add(cell);
                }
            }
        }
        JsonArray displays = new JsonArray();
        // A display's box has no size and intersection is strict, so look past the cell edges.
        AABB bounds = new AABB(x1 - 0.5, y1 - 0.5, z1 - 0.5, x2 + 1.5, y2 + 1.5, z2 + 1.5);
        for (Entity entity : level.getEntities((Entity) null, bounds, candidate -> candidate instanceof Display)) {
            JsonObject display = new JsonObject();
            display.addProperty("id", entity.getId());
            display.addProperty("at", faweTest$sixtyFourths(entity.getX() - x1) + ","
                + faweTest$sixtyFourths(entity.getY() - y1) + "," + faweTest$sixtyFourths(entity.getZ() - z1));
            display.addProperty("shows", entity instanceof Display.BlockDisplay block
                ? BlockStateParser.serialize(((BlockDisplayAccessor) block).faweTest$blockState())
                : entity.getType().toString());
            displays.add(display);
        }
        JsonObject out = new JsonObject();
        out.addProperty("loaded", loaded);
        out.add("cells", cells);
        out.add("displays", displays);
        return out;
    }

    @Unique
    private static String faweTest$sixtyFourths(double value) {
        return Double.toString(Math.round(value * 64) / 64.0);
    }

    // Each line is a complete typed observation, not a successful resource-pack reply.
    @Unique
    private static synchronized void faweTest$event(JsonObject event) throws IOException {
        Files.writeString(Path.of(System.getProperty("fawe.test.events")), event + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
