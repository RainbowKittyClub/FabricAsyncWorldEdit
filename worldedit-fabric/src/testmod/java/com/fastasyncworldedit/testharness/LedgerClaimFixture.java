package com.fastasyncworldedit.testharness;

import java.util.Objects;
import java.util.UUID;

import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.player.config.api.v2.IPlayerConfigAPI;
import xaero.pac.common.server.player.config.api.v2.IPlayerConfigOptionSpecAPI;
import xaero.pac.common.server.player.config.api.v2.PlayerConfigOptions;

import net.minecraft.server.MinecraftServer;

/** S6's claim fixture for the S8 lane: chunk 200,200 claimed and protected for a stranger. */
final class LedgerClaimFixture {
    private static final UUID STRANGER = UUID.fromString("e151edcc-b621-44ae-b30c-6d4f03503711");

    private LedgerClaimFixture() {
    }

    static void claimForStranger(MinecraftServer server) {
        var api = OpenPACServerAPI.get(server);
        var dimension = server.overworld().dimension().identifier();
        api.getServerClaimsManager().unclaim(dimension, 200, 200);
        if (api.getServerClaimsManager().claim(dimension, STRANGER, 0, 200, 200, false) == null) {
            throw new IllegalStateException("Claim fixture failed");
        }
        IPlayerConfigAPI config = api.getChunkProtection()
                .getConfig(api.getServerClaimsManager().get(dimension, 200, 200), dimension);
        require(config, PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS, true);
        require(config, PlayerConfigOptions.FULL_ACCESS, "P");
    }

    // Require fixture changes to be effective; never substitute a weaker configuration on failure.
    private static <T> void require(IPlayerConfigAPI config, IPlayerConfigOptionSpecAPI<T> option, T value) {
        config.tryToSet(option, value);
        if (!Objects.equals(config.getEffective(option), value)) {
            throw new IllegalStateException("OPAC fixture option did not take effect: " + option);
        }
    }
}
