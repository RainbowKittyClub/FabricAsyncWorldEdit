package com.fastasyncworldedit.fabric.edit;

import java.util.HashSet;
import java.util.Set;

import com.sk89q.worldedit.internal.util.LogManagerCompat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * Which blocks have a verified lifecycle. A short base set is verified here; a mod adds its own
 * blocks with a declaration in its fabric.mod.json:
 *
 * <pre>
 * "custom": { "fawe:lifecycle": { "blocks": ["modid:block", "#modid:tag"], "route": "native" } }
 * </pre>
 *
 * Declaring a block says the mod's lifecycle hooks run when the block is set through
 * {@code LevelChunk.setBlockState}, the per-cell route. {@code "route": "participant"} also says the
 * mod registers a {@code polymer:bulk_edits} participant that repairs the same state after a direct
 * section write; only then may the block be installed into a section directly. Without that
 * participant the declaration stays on the per-cell route. A declaration that cannot be read is
 * ignored whole, never partly.
 */
public final class LifecycleDeclarations {
    /** The custom value a mod declares its blocks under. */
    public static final String KEY = "fawe:lifecycle";
    private static final String BULK_ENTRYPOINT = "polymer:bulk_edits";

    // Verified here: simple blocks and vanilla containers, on either route.
    private static final Set<String> BASE = Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:stone",
            "minecraft:dirt", "minecraft:cobblestone", "minecraft:glass", "minecraft:glowstone",
            "minecraft:oak_log", "minecraft:birch_log", "minecraft:stripped_oak_log",
            "minecraft:acacia_log", "minecraft:jungle_log", "minecraft:redstone_lamp",
            "minecraft:oak_planks", "minecraft:grass_block", "minecraft:water",
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:furnace",
            "minecraft:bell", "minecraft:lectern", "minecraft:gold_block", "minecraft:diamond_block");

    /** Declared blocks by registry id, and those among them a participant covers. */
    private record Snapshot(Set<String> declared, Set<String> participant) { }

    private static volatile Snapshot snapshot = new Snapshot(Set.of(), Set.of());

    private LifecycleDeclarations() {
    }

    /** Whether a block type, by registry id, has verified lifecycle support. */
    public static boolean supports(String blockId) {
        return BASE.contains(blockId) || snapshot.declared.contains(blockId);
    }

    /**
     * Whether a block may sit in a section that is installed directly. Vanilla blocks may unless a
     * mod declared them; a mod's own blocks only when its participant covers them.
     */
    public static boolean directSafe(String blockId) {
        var current = snapshot;
        if (blockId.startsWith("minecraft:")) {
            return !current.declared.contains(blockId) || current.participant.contains(blockId);
        }
        return current.participant.contains(blockId);
    }

    /** The declared block ids, sorted, for diagnostics. */
    public static java.util.List<String> declared() {
        return snapshot.declared.stream().sorted().toList();
    }

    /**
     * Read every mod's declaration against the loaded registries and tags. Owner thread, after
     * the tags are bound: at server start and after each data pack reload.
     */
    public static void refresh() {
        Set<String> participantMods = new HashSet<>();
        FabricLoader.getInstance().getEntrypointContainers(BULK_ENTRYPOINT, Object.class)
                .forEach(container -> participantMods.add(container.getProvider().getMetadata().getId()));
        Set<String> declared = new HashSet<>();
        Set<String> participant = new HashSet<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            var metadata = mod.getMetadata();
            if (!metadata.containsCustomValue(KEY)) {
                continue;
            }
            try {
                var value = metadata.getCustomValue(KEY).getAsObject();
                boolean viaParticipant = routeIsParticipant(value.get("route"));
                if (viaParticipant && !participantMods.contains(metadata.getId())) {
                    LogManagerCompat.getLogger().warn("{} declares route \"participant\" but registers no {} entrypoint;"
                            + " its blocks stay on the per-cell route", metadata.getId(), BULK_ENTRYPOINT);
                    viaParticipant = false;
                }
                Set<String> blocks = resolve(value.get("blocks"));
                declared.addAll(blocks);
                if (viaParticipant) {
                    participant.addAll(blocks);
                }
            } catch (RuntimeException failure) {
                LogManagerCompat.getLogger().warn("Ignoring the {} declaration of {}: {}", KEY, metadata.getId(),
                        failure.getMessage());
            }
        }
        snapshot = new Snapshot(Set.copyOf(declared), Set.copyOf(participant));
    }

    private static boolean routeIsParticipant(CustomValue route) {
        if (route == null) {
            return false;
        }
        return switch (route.getAsString()) {
            case "native" -> false;
            case "participant" -> true;
            default -> throw new IllegalArgumentException("unknown route " + route.getAsString());
        };
    }

    // Block ids and tags to registry ids; an id or tag that matches nothing is simply empty.
    private static Set<String> resolve(CustomValue blocks) {
        if (blocks == null || blocks.getType() != CustomValue.CvType.ARRAY) {
            throw new IllegalArgumentException("\"blocks\" must be an array");
        }
        Set<String> ids = new HashSet<>();
        for (CustomValue entry : blocks.getAsArray()) {
            String text = entry.getAsString();
            if (text.startsWith("#")) {
                TagKey<Block> tag = TagKey.create(Registries.BLOCK, Identifier.parse(text.substring(1)));
                BuiltInRegistries.BLOCK.listElements().forEach(holder -> {
                    if (holder.is(tag)) {
                        ids.add(holder.key().identifier().toString());
                    }
                });
            } else {
                Identifier id = Identifier.parse(text);
                if (BuiltInRegistries.BLOCK.containsKey(id)) {
                    ids.add(id.toString());
                }
            }
        }
        return ids;
    }
}
