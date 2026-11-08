package com.fastasyncworldedit.fabric.edit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Owner-provided, versioned codecs for one position's portable chunk attachment data. */
public final class PositionDataRegistry {
    private static volatile PositionDataRegistry active;
    private static final Set<String> FIELDS = Set.of("id", "version", "holds", "read", "prepare");
    private final MinecraftServer server;
    private final Map<String, Codec> codecs;
    private final Set<String> emptyStable;

    /** Resolve this running server's owner snapshot without recovering a worker-thread actor. */
    public static PositionDataRegistry get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Position data requires the server thread");
        if (active == null) active = new PositionDataRegistry(server);
        if (active.server != server) throw new IllegalStateException("Foreign position-data server");
        return active;
    }

    /** Pure capability query used by detached clipboard/material code. */
    public static boolean portable(BlockState state) {
        PositionDataRegistry current = active;
        return current != null && current.holds(state);
    }

    /** Release a stopped world's owner callbacks; another server must rediscover its suppliers. */
    public static void stop(MinecraftServer server) {
        if (active != null && active.server == server) {
            active.owner();
            active = null;
        }
    }

    /** Discover optional suppliers only after all consumer initialization has finished. */
    public PositionDataRegistry(MinecraftServer server) {
        this.server = server;
        owner();
        Map<String, Codec> registered = new LinkedHashMap<>();
        Map<String, String> providers = new LinkedHashMap<>();
        for (var entry : FabricLoader.getInstance().getEntrypointContainers("fawe:position_data", Supplier.class)) {
            Object declarations = entry.getEntrypoint().get();
            if (!(declarations instanceof List<?> list)) {
                throw new IllegalArgumentException("Invalid position-data supplier from "
                        + entry.getProvider().getMetadata().getId());
            }
            for (Object declaration : list) {
                Codec codec = decode(declaration);
                if (registered.putIfAbsent(codec.id(), codec) != null) {
                    throw new IllegalArgumentException("Duplicate position-data owner: " + codec.id());
                }
                providers.put(codec.id(), entry.getProvider().getMetadata().getId());
                if (registered.size() > 256) {
                    throw new IllegalArgumentException("More than 256 position-data owners");
                }
            }
        }
        codecs = java.util.Collections.unmodifiableMap(registered);
        Set<String> stable = new java.util.HashSet<>();
        for (var entry : FabricLoader.getInstance().getEntrypointContainers(
                "fawe:position_data_empty_stable", Supplier.class)) {
            Object declarations = entry.getEntrypoint().get();
            if (!(declarations instanceof List<?> list)) {
                throw new IllegalArgumentException("Invalid empty-stable position-data declaration");
            }
            for (Object declaration : list) {
                if (!(declaration instanceof String id)
                        || !entry.getProvider().getMetadata().getId().equals(providers.get(id))) {
                    throw new IllegalArgumentException("Empty-stable declaration must name its own codec: " + declaration);
                }
                stable.add(id);
            }
        }
        emptyStable = Set.copyOf(stable);
    }

    /**
     * Whether every matching owner explicitly permits skipping cleanup hooks with no stored data.
     * Callers must also prove the entire chunk has no attachments and no proposed present values.
     */
    public static boolean stableWhenEmpty(BlockState state) {
        PositionDataRegistry current = active;
        return current == null || current.codecs.values().stream().allMatch(codec ->
                !codec.holds().test(state) || current.emptyStable.contains(codec.id()));
    }

    /** The explicit opt-ins, for diagnostics; absent declarations always retain the native route. */
    public Set<String> emptyStableOwners() {
        return emptyStable;
    }

    /** Whether this exact chunk attachment has a registered portable owner. */
    public boolean owns(String id) {
        return codecs.containsKey(id);
    }

    /** Whether a snapshot may contain owner data and needs sensitive-read authorization. */
    public boolean hasOwners() {
        return !codecs.isEmpty();
    }

    /** Immutable owner/version inventory for diagnostics and replay capability checks. */
    public Map<String, Integer> versions() {
        return codecs.values().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Codec::id, Codec::version));
    }

    /** Pure block-type capability check; it does not inspect or mutate a live attachment. */
    public boolean holds(BlockState state) {
        return codecs.values().stream().anyMatch(codec -> codec.holds().test(state));
    }

    /** Capture detached values, including explicit absence for every owner accepting this state. */
    public FrozenNbt read(ServerLevel level, BlockPos pos, BlockState state) {
        loaded(level, pos);
        CompoundTag result = new CompoundTag();
        for (Codec codec : codecs.values()) {
            CompoundTag value = codec.read().apply(level, pos);
            if (!codec.holds().test(state)) {
                if (value != null) {
                    throw new IllegalStateException("Stale position data " + codec.id() + " at " + pos);
                }
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt("version", codec.version());
            if (value != null) entry.put("value", value.copy());
            result.put(codec.id(), entry);
        }
        if (result.isEmpty()) return null;
        if (result.sizeInBytes() > 65536) {
            throw new IllegalArgumentException("Position data exceeds 64 KiB at " + pos);
        }
        return FrozenNbt.capture(result);
    }

    /** Validate the whole detached payload before returning any action that mutates the world. */
    public Consumer<BlockPos> prepare(ServerLevel level, BlockState target, FrozenNbt frozen) {
        owner(level);
        if (frozen == null) return pos -> loaded(level, pos);
        CompoundTag payload = frozen.thaw();
        Set<String> expected = codecs.values().stream().filter(codec -> codec.holds().test(target))
                .map(Codec::id).collect(java.util.stream.Collectors.toSet());
        if (!payload.keySet().equals(expected)) {
            throw new IllegalArgumentException("Position-data owners do not match target: " + payload.keySet());
        }
        List<Consumer<BlockPos>> actions = new ArrayList<>();
        for (String id : payload.keySet()) {
            Codec codec = codecs.get(id);
            if (!(payload.get(id) instanceof CompoundTag entry)
                    || !(entry.get("version") instanceof IntTag version)
                    || version.intValue() != codec.version()
                    || !Set.of("version", "value").containsAll(entry.keySet())
                    || entry.contains("value") && !(entry.get("value") instanceof CompoundTag)) {
                throw new IllegalArgumentException("Invalid or unsupported position-data version: " + id);
            }
            Optional<CompoundTag> value = Optional.ofNullable((CompoundTag) entry.get("value"))
                    .map(CompoundTag::copy);
            actions.add(java.util.Objects.requireNonNull(codec.prepare().apply(level, value),
                    "Position-data owner returned no restore action: " + id));
        }
        return pos -> {
            loaded(level, pos);
            if (!level.getBlockState(pos).equals(target)) {
                throw new IllegalStateException("Position-data target changed at " + pos);
            }
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            ((ChunkRevision) chunk).worldedit$advanceEditRevision();
            chunk.markUnsaved();
            actions.forEach(action -> action.accept(pos));
        };
    }

    // Type erasure is confined to the validated optional protocol boundary.
    @SuppressWarnings("unchecked")
    private static Codec decode(Object declaration) {
        if (!(declaration instanceof Map<?, ?> map) || !map.keySet().equals(FIELDS)
                || !(map.get("id") instanceof String id)
                || !(map.get("version") instanceof Integer version) || version < 1
                || !(map.get("holds") instanceof Predicate<?>)
                || !(map.get("read") instanceof BiFunction<?, ?, ?>)
                || !(map.get("prepare") instanceof BiFunction<?, ?, ?>)) {
            throw new IllegalArgumentException("Invalid position-data codec declaration");
        }
        if (!Identifier.parse(id).toString().equals(id)) {
            throw new IllegalArgumentException("Position-data id must be namespaced: " + id);
        }
        return new Codec(id, version, (Predicate<BlockState>) map.get("holds"),
                (BiFunction<ServerLevel, BlockPos, CompoundTag>) map.get("read"),
                (BiFunction<ServerLevel, Optional<CompoundTag>, Consumer<BlockPos>>) map.get("prepare"));
    }

    // Payload actions never acquire a new chunk, including when invoked after their preparation.
    private void loaded(ServerLevel level, BlockPos pos) {
        owner(level);
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            throw new IllegalStateException("Position-data chunk is not loaded at " + pos);
        }
    }

    // A supplier and its owner callbacks belong to this running server only.
    private void owner(ServerLevel level) {
        owner();
        if (level.getServer() != server) throw new IllegalStateException("Foreign position-data level");
    }

    // No live owner callback runs from detached preparation workers.
    private void owner() {
        if (!server.isSameThread()) throw new IllegalStateException("Position data requires the server thread");
    }

    private record Codec(String id, int version, Predicate<BlockState> holds,
            BiFunction<ServerLevel, BlockPos, CompoundTag> read,
            BiFunction<ServerLevel, Optional<CompoundTag>, Consumer<BlockPos>> prepare) { }
}
