package com.fastasyncworldedit.fabric.compat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;

/** Fail-closed optional-provider discovery; each integration owns only its implementation package. */
public final class EditIntegrations {
    private static final Map<String, String> PROVIDERS = Map.of(
            "openpartiesandclaims", "com.fastasyncworldedit.fabric.compat.opac.OpacAuthorizer",
            "polymer-core", "com.fastasyncworldedit.fabric.compat.polymer.PolymerEditBridge",
            "ledger", "com.fastasyncworldedit.fabric.compat.ledger.LedgerEditBridge");

    // No mutable global registration surface survives server restarts.
    private EditIntegrations() {
    }

    /**
     * Whether whole-section publication may bypass vanilla block-change hooks.
     *
     * <p>Polymer Virtual Entity depends only on polymer-common, so a core-free install can own
     * block-bound holders while the bridge is not discovered (discovery keys on polymer-core).
     * The direct-section route skips {@code LevelChunk.setBlockState}, so writing it in that
     * topology would leave holders stale. Force the ordinary per-cell lifecycle instead.</p>
     */
    public static boolean directSectionRouteSupported() {
        FabricLoader loader = FabricLoader.getInstance();
        return !loader.isModLoaded("polymer-virtual-entity") || loader.isModLoaded("polymer-core");
    }

    /** Load only providers for installed mods; initialization failure is never absence. */
    public static List<EditIntegration> discover() {
        List<EditIntegration> result = new ArrayList<>();
        PROVIDERS.forEach((mod, implementation) -> {
            if (FabricLoader.getInstance().isModLoaded(mod)) {
                try {
                    result.add((EditIntegration) Class.forName(implementation).getConstructor().newInstance());
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Required edit integration is unavailable: " + mod, failure);
                }
            }
        });
        result.addAll(FabricLoader.getInstance().getEntrypoints("fawe:edit_integration", EditIntegration.class));
        validate(result);
        return List.copyOf(result);
    }

    /** Also validate explicitly supplied providers, so API callers cannot bypass installed integrations. */
    public static void validate(List<EditIntegration> providers) {
        Set<String> ids = new HashSet<>();
        for (EditIntegration provider : providers) {
            if (!ids.add(provider.id())) {
                throw new IllegalArgumentException("Duplicate edit integration: " + provider.id());
            }
        }
        for (String mod : PROVIDERS.keySet()) {
            if (FabricLoader.getInstance().isModLoaded(mod) && !ids.contains(mod)) {
                throw new IllegalStateException("Installed integration has no provider: " + mod);
            }
        }
        for (String optimization : List.of("lithium", "c2me", "scalablelux")) {
            if (FabricLoader.getInstance().isModLoaded(optimization)) {
                throw new UnsupportedOperationException("Optimization lifecycle is not validated: " + optimization);
            }
        }
    }
}
