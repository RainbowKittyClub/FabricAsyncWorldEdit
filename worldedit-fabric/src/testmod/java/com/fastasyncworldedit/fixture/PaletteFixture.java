package com.fastasyncworldedit.fixture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import net.fabricmc.api.ModInitializer;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/** Shared server/client test registry, enabled only for the S4 palette suite. */
public final class PaletteFixture implements ModInitializer {
    public static final IntegerProperty VARIANT = IntegerProperty.create("variant", 0, 1023);
    public static Block block;

    @Override
    public void onInitialize() {
        if (Boolean.getBoolean("fawe.test.s4")) {
            var key = ResourceKey.create(Registries.BLOCK, Identifier.parse("fawe_test:palette"));
            block = Registry.register(BuiltInRegistries.BLOCK, key,
                    new PaletteBlock(BlockBehaviour.Properties.of().setId(key)));
        }
    }

    /** Describe every state and wire ID after registry synchronization, not just its count. */
    public static Map<String, Object> describe() {
        if (block == null) {
            throw new IllegalStateException("S4 palette fixture not enabled");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int variant = 0; variant < 1024; variant++) {
                BlockState state = block.defaultBlockState().setValue(VARIANT, variant);
                digest.update(("fawe_test:palette[variant=" + variant + "]="
                        + Block.BLOCK_STATE_REGISTRY.getId(state) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return Map.of("states", block.getStateDefinition().getPossibleStates().size(),
                    "sha256", HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    // Real high-cardinality block forces native global palettes without any Polymer handshake.
    private static final class PaletteBlock extends Block {
        private PaletteBlock(BlockBehaviour.Properties properties) { super(properties); }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(VARIANT);
        }
    }
}
