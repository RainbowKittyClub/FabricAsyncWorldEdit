package com.fastasyncworldedit.s6;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.google.gson.Gson;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.sk89q.worldedit.fabric.internal.NBTConverter;
import com.sk89q.worldedit.history.changeset.ArrayListHistory;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.biome.BiomeTypes;
import com.sk89q.worldedit.world.block.BlockState;
import me.lucko.fabric.api.permissions.v0.PermissionCheckEvent;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.util.TriState;
import xaero.pac.common.parties.party.member.PartyMemberRank;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.player.config.api.v2.IPlayerConfigAPI;
import xaero.pac.common.server.player.config.api.v2.PlayerConfigOptions;
import xaero.pac.common.server.player.data.ServerPlayerData;
import xaero.pac.common.server.player.data.api.ServerPlayerDataAPI;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.block.Blocks;

import com.fastasyncworldedit.fabric.compat.EditIntegration;
import com.fastasyncworldedit.fabric.edit.CommitReceipt;
import com.fastasyncworldedit.fabric.edit.EditActorContext;
import com.fastasyncworldedit.fabric.edit.EditDelta;
import com.fastasyncworldedit.fabric.edit.FabricEdits;
import com.fastasyncworldedit.fabric.edit.PreparedChunkEdit;

/** Non-shipped real-OPAC fixtures; mutation under test always uses the production FabricEdits API. */
public final class ClaimsProbe implements ModInitializer, EditIntegration {
    private static final UUID OTHER = UUID.fromString("e151edcc-b621-44ae-b30c-6d4f03503711");
    private static final BlockPos POS = new BlockPos(3200, 80, 3200);
    private static final Gson JSON = new Gson();
    private static final List<Map<String, Object>> results = new ArrayList<>();
    private static MinecraftServer server;
    private static ServerPlayer actor;
    private static Runnable race;
    private static boolean enabled;
    private static boolean done;
    private static boolean disconnectDone;
    private static String failure = "";
    private static int recorded;
    public static volatile boolean mutationFault;
    public static volatile int mutationCalls;
    public static volatile boolean brokenProvider;
    private static volatile boolean finalizerFault;
    private static volatile int finalizerCalls;
    private static final List<CommitReceipt> nativeReceipts = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static xaero.pac.common.server.claims.protection.override.api.ChunkAccessOverrideType override;

    @Override
    public void onInitialize() {
        xaero.pac.common.event.api.v3.OPACServerAddonRegister.EVENT.register(event ->
                event.getChunkAccessOverriderManagerAPI().register(new ClaimOverride()));
        PermissionCheckEvent.EVENT.register((source, node) -> enabled
                && (node.startsWith("worldedit.") || node.startsWith("fawe."))
                ? TriState.TRUE : TriState.DEFAULT);
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(
                Commands.literal("s6probe").then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(command -> {
                            String[] words = StringArgumentType.getString(command, "args").split(" ");
                            server = command.getSource().getServer();
                            if (words[0].equals("border-saved")) {
                                verifyBorderWorld(server.overworld(), Blocks.OAK_PLANKS);
                                command.getSource().sendSuccess(() -> Component.literal("{\"savedBorders\":true}"), false);
                                return 1;
                            }
                            if (words[0].equals("start")) {
                                actor = server.getPlayerList().getPlayerByName(words[1]);
                                enabled = true;
                                done = false;
                                failure = "";
                                results.clear();
                                run(0);
                            } else if (words[0].equals("native-history")) {
                                actor = server.getPlayerList().getPlayerByName(words[1]);
                                enabled = true;
                                done = false;
                                failure = "";
                                results.clear();
                                nativeHistory();
                            } else if (words[0].equals("edges")) {
                                actor = server.getPlayerList().getPlayerByName(words[1]);
                                enabled = true;
                                done = false;
                                failure = "";
                                results.clear();
                                nativeEdges();
                            } else if (words[0].equals("borders")) {
                                done = false;
                                failure = "";
                                results.clear();
                                nativeBorders();
                            } else if (words[0].equals("claim")) {
                                claim(words[1].equals("owner") ? actor.getUUID() : OTHER);
                            } else if (words[0].equals("native-reset")) {
                                nativeReceipts.clear();
                            } else if (words[0].equals("disconnect")) {
                                disconnect();
                            }
                            var session = com.sk89q.worldedit.WorldEdit.getInstance().getSessionManager()
                                    .get(com.sk89q.worldedit.fabric.FabricAdapter.adaptPlayer(actor));
                            command.getSource().sendSuccess(() -> Component.literal(JSON.toJson(Map.of(
                                    "done", done, "disconnectDone", disconnectDone, "failure", failure, "results", results,
                                    "nativeReceipts", nativeReceipts.stream().map(receipt -> Map.of(
                                            "actor", receipt.context().actorId().toString(),
                                            "operation", receipt.context().operationId().toString(),
                                            "source", receipt.context().source().name(),
                                            "parent", java.util.Objects.toString(receipt.context().parentId(), "unknown"),
                                            "originalActor", java.util.Objects.toString(receipt.context().originalActorId(), "unknown"),
                                            "session", java.util.Objects.toString(receipt.context().sessionId(), "unknown"),
                                            "applied", receipt.applied().size(), "denied", receipt.denied(),
                                            "noOp", receipt.noOp(), "outcome", receipt.outcome().name())).toList(),
                                    "historySizes", session.getHistory().stream().map(history -> history.size()).toList()))), false);
                            return 1;
                        }))));
    }

    @Override
    public String id() { return "fawe-s6-probe"; }

    @Override
    public Set<EditDelta.Kind> capabilities() { return Set.of(EditDelta.Kind.values()); }

    @Override
    public CompletionStage<Reservation> prepare(PreparedChunkEdit proposal) {
        CompletableFuture<Reservation> result = new CompletableFuture<>();
        if (race != null) {
            server.execute(() -> {
                try {
                    Runnable change = race;
                    race = null;
                    change.run();
                    result.complete(new Observation());
                } catch (Throwable problem) {
                    result.completeExceptionally(problem);
                }
            });
        } else {
            result.complete(new Observation());
        }
        return result;
    }

    // This real OPAC addon is scoped to the synthetic actor and one fixture chunk.
    private static final class ClaimOverride implements
            xaero.pac.common.server.claims.protection.override.api.IChunkAccessOverriderAPI {
        @Override
        public String getName() { return "FAWE isolated claim fixture"; }

        @Override
        public xaero.pac.common.server.claims.protection.override.api.ChunkAccessOverride overrideChunkAccess(
                net.minecraft.resources.Identifier dimension, int x, int z, IPlayerConfigAPI config,
                net.minecraft.world.entity.Entity accessor, UUID id, MinecraftServer current,
                xaero.pac.common.server.claims.protection.override.api.ChunkAccessOverride previous) {
            if (override == null || current != server || actor == null || !actor.getUUID().equals(id)
                    || x != 200 || z != 200 || !current.overworld().dimension().identifier().equals(dimension)) {
                return previous;
            }
            return new xaero.pac.common.server.claims.protection.override.api.ChunkAccessOverride(override);
        }
    }

    // Only actual committed receipts contribute audit-observer entries.
    private static final class Observation implements Reservation {
        @Override
        public boolean authorize(ServerLevel level, ServerPlayer operator, EditActorContext context, EditDelta delta) {
            return true;
        }
        @Override
        public void beforePublication(ServerLevel level, List<EditDelta> authorized) { }
        @Override
        public CompletionStage<Void> reconcile(ServerLevel level, CommitReceipt receipt) {
            if (finalizerFault && receipt.applied().stream().anyMatch(applied -> applied.delta().x() == 3248)) {
                finalizerCalls++;
                return CompletableFuture.failedFuture(new IllegalStateException("s6-required-finalizer-failure"));
            }
            return CompletableFuture.completedFuture(null);
        }
        @Override
        public CompletionStage<Void> record(CommitReceipt receipt) {
            recorded += receipt.applied().size();
            if (enabled && receipt.context().sessionId() != null) {
                nativeReceipts.add(receipt);
            }
            return CompletableFuture.completedFuture(null);
        }
        @Override
        public void close(ServerLevel level) { }
        @Override
        public void discard() { }
    }

    // Every scenario starts from an explicit world value and fresh claim/mode state.
    private static void run(int index) {
        String[] names = {"owner", "stranger", "party-member", "party-revoked", "claim-transferred",
                "config-revoked", "nonally", "nonally-race", "plain-op", "admin", "admin-revoked", "impersonation",
                "impersonation-revoked", "biome-owner", "biome-stranger", "nbt-owner", "nbt-stranger",
                "undo-current-operator", "rollback-current-operator", "readonly-owner", "readonly-stranger",
                "ally-default", "ally-access", "ally-revoked", "wilderness", "wilderness-protected",
                "server-claim", "server-admin", "addon-allow", "addon-protect-owner", "addon-revoked"};
        if (index == names.length) {
            override = null;
            modes().setClaimsAdminMode(false);
            modes().setClaimsNonallyMode(false);
            modes().getClaimsImpersonationInfo().reset();
            done = true;
            return;
        }
        String name = names[index];
        try {
            ServerLevel level = server.overworld();
            NameAndId identity = new NameAndId(actor.getUUID(), actor.getGameProfile().name());
            if (name.equals("plain-op") || name.startsWith("admin") || name.startsWith("impersonation")
                    || name.equals("server-admin")) {
                server.getPlayerList().op(identity);
            } else {
                server.getPlayerList().deop(identity);
            }
            override = null;
            var api = OpenPACServerAPI.get(server);
            var parties = api.getPartyManager();
            parties.removePartyByOwner(OTHER);
            parties.removePartyByOwner(actor.getUUID());
            modes().setClaimsAdminMode(false);
            modes().setClaimsNonallyMode(false);
            modes().getClaimsImpersonationInfo().reset();
            level.setBlock(POS, Blocks.AIR.defaultBlockState(), 3);
            boolean sensitive = name.startsWith("nbt") || name.startsWith("readonly");
            level.setBlock(POS, sensitive ? Blocks.CHEST.defaultBlockState() : Blocks.STONE.defaultBlockState(), 3);
            if (sensitive) {
                ((net.minecraft.world.Container) level.getBlockEntity(POS)).setItem(0,
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 7));
            }
            boolean owner = name.equals("owner") || name.equals("claim-transferred") || name.endsWith("owner");
            claim(owner ? actor.getUUID() : OTHER);
            boolean permitted = owner;
            if (name.startsWith("party") || name.startsWith("nonally")) {
                var party = parties.createPartyForOwner(new NameAndId(OTHER, "s6owner"));
                party.addMember(actor.getUUID(), PartyMemberRank.MEMBER, actor.getGameProfile().name());
                permitted = true;
                if (name.equals("party-revoked")) {
                    race = () -> party.removeMember(actor.getUUID());
                    permitted = false;
                } else if (name.equals("nonally")) {
                    modes().setClaimsNonallyMode(true);
                    permitted = false;
                } else if (name.equals("nonally-race")) {
                    race = () -> modes().setClaimsNonallyMode(true);
                    permitted = false;
                }
            }
            if (name.equals("claim-transferred")) {
                race = () -> claim(OTHER);
                permitted = false;
            }
            if (name.equals("config-revoked")) {
                config().tryToSet(PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS, false);
                race = () -> setProtected(true);
                permitted = false;
            }
            if (name.startsWith("admin")) {
                modes().setClaimsAdminMode(true);
                permitted = true;
                if (name.equals("admin-revoked")) {
                    race = () -> modes().setClaimsAdminMode(false);
                    permitted = false;
                }
            }
            if (name.startsWith("impersonation")) {
                modes().getClaimsImpersonationInfo().setPlayerId(OTHER);
                permitted = true;
                if (name.equals("impersonation-revoked")) {
                    race = () -> modes().getClaimsImpersonationInfo().reset();
                    permitted = false;
                }
            }
            if (name.startsWith("ally")) {
                var ownerParty = parties.createPartyForOwner(new NameAndId(OTHER, "s6owner"));
                var actorParty = parties.createPartyForOwner(identity);
                ownerParty.addAllyParty(actorParty.getId());
                actorParty.addAllyParty(ownerParty.getId());
                if (!name.equals("ally-default")) {
                    setOption(PlayerConfigOptions.FULL_ACCESS, "A");
                    permitted = true;
                }
                if (name.equals("ally-revoked")) {
                    race = () -> ownerParty.removeAllyParty(actorParty.getId());
                    permitted = false;
                }
            }
            if (name.startsWith("wilderness")) {
                api.getServerClaimsManager().unclaim(level.dimension().identifier(), 200, 200);
                permitted = name.equals("wilderness");
                if (permitted && config().getEffective(PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS)) {
                    throw new AssertionError("Expected default unprotected wilderness fixture");
                }
                if (!permitted) {
                    setProtected(true);
                }
            }
            if (name.startsWith("server-")) {
                claim(new UUID(0, 0));
                permitted = name.equals("server-admin");
                modes().setClaimsAdminMode(permitted);
            }
            if (name.startsWith("addon-")) {
                permitted = name.equals("addon-allow");
                override = name.equals("addon-protect-owner")
                        ? xaero.pac.common.server.claims.protection.override.api.ChunkAccessOverrideType.PROTECT
                        : xaero.pac.common.server.claims.protection.override.api.ChunkAccessOverrideType.ALLOW;
                if (name.equals("addon-revoked")) {
                    race = () -> override = null;
                }
            }
            EditActorContext.Source source = name.startsWith("undo") ? EditActorContext.Source.UNDO
                    : name.startsWith("rollback") ? EditActorContext.Source.LEDGER_ROLLBACK : EditActorContext.Source.API;
            EditActorContext context = new EditActorContext(actor.getUUID(), actor.getGameProfile().name(), source,
                    UUID.randomUUID(), source == EditActorContext.Source.API ? null : UUID.randomUUID(), OTHER,
                    level.dimension().identifier().toString(), SideEffectSet.none(), new EditActorContext.Cancellation());
            if (index == 0) {
                refuseCompounds(context, level);
            }
            var history = new ArrayListHistory();
            recorded = 0;
            boolean expected = permitted;
            var before = level.getBlockState(POS);
            var originalBiome = level.getChunk(200, 200).getNoiseBiome(800, 20, 800).unwrapKey().orElseThrow();
            var callbackRan = new java.util.concurrent.atomic.AtomicBoolean();
            var observed = new java.util.concurrent.atomic.AtomicReference<String>();
            FabricEdits.get(server).submitIndependent(context, List.of(BlockVector2.at(200, 200)), history, queue -> {
                callbackRan.set(true);
                if (name.startsWith("readonly")) {
                    observed.set(queue.getFullBlock(BlockVector3.at(3200, 80, 3200)).getNbt().toString());
                } else if (name.startsWith("biome")) {
                    queue.setBiome(3200, 80, 3200, name.endsWith("owner") ? BiomeTypes.DESERT : BiomeTypes.FOREST);
                } else if (name.startsWith("nbt")) {
                    CompoundTag tag = new CompoundTag();
                    tag.putString("id", "minecraft:chest");
                    tag.putString("CustomName", "\"s6-protected\"");
                    queue.setBlock(BlockVector3.at(3200, 80, 3200),
                            BlockState.get("minecraft:chest[facing=north,type=single,waterlogged=false]")
                                    .toBaseBlock(NBTConverter.fromNative(tag)));
                } else {
                    queue.setBlock(BlockVector3.at(3200, 80, 3200), BlockState.get("minecraft:gold_block"));
                }
            }).whenComplete((receipts, problem) -> server.execute(() -> {
                try {
                    if (sensitive && !expected) {
                        Throwable cause = problem;
                        while (cause != null && !(cause instanceof com.fastasyncworldedit.fabric.compat.NativeClaims.RefusedRead)) {
                            cause = cause.getCause();
                        }
                        if (cause == null || callbackRan.get() || history.size() != 0 || recorded != 0
                                || ((net.minecraft.world.Container) level.getBlockEntity(POS)).getItem(0).getCount() != 7) {
                            throw new AssertionError(name + " failed pre-read refusal", problem);
                        }
                        results.add(Map.of("case", name, "outcome", "refused-before-callback", "history", 0,
                                "receiptObserver", 0, "callbackRan", false, "inventoryCount", 7));
                        run(index + 1);
                        return;
                    }
                    if (problem != null) throw new AssertionError(name, problem);
                    if (name.startsWith("readonly")) {
                        if (!callbackRan.get() || observed.get() == null || !observed.get().contains("minecraft:diamond")
                                || history.size() != 0 || recorded != 0
                                || receipts.stream().anyMatch(receipt -> !receipt.applied().isEmpty())) {
                            throw new AssertionError(name + " missing read-only positive control");
                        }
                        results.add(Map.of("case", name, "outcome", "authorized-read-only", "history", 0,
                                "receiptObserver", 0, "callbackRan", true, "inventoryCount", 7));
                        run(index + 1);
                        return;
                    }
                    int applied = receipts.stream().mapToInt(receipt -> receipt.applied().size()).sum();
                    int denied = receipts.stream().mapToInt(CommitReceipt::denied).sum();
                    int count = expected ? 1 : 0;
                    if (applied != count || history.size() != count || recorded != count || denied != 1 - count) {
                        throw new AssertionError(name + " applied=" + applied + " denied=" + denied
                                + " history=" + history.size() + " observed=" + recorded);
                    }
                    if (name.startsWith("biome")) {
                        var actual = level.getChunk(200, 200).getNoiseBiome(800, 20, 800).unwrapKey().orElseThrow();
                        if (expected ? !actual.identifier().toString().equals("minecraft:desert") : !actual.equals(originalBiome)) {
                            throw new AssertionError(name + " biome=" + actual);
                        }
                    } else if (name.startsWith("nbt")) {
                        String text = level.getBlockEntity(POS).saveWithFullMetadata(level.registryAccess()).toString();
                        if (text.contains("s6-protected") != expected) throw new AssertionError(name + " nbt=" + text);
                    } else if (level.getBlockState(POS) != (expected ? Blocks.GOLD_BLOCK.defaultBlockState() : before)) {
                        throw new AssertionError(name + " wrong world value");
                    }
                    results.add(Map.of("case", name, "applied", applied, "denied", denied, "history", history.size(),
                            "receiptObserver", recorded));
                    run(index + 1);
                } catch (Throwable error) { fail(error); }
            }));
        } catch (Throwable error) { fail(error); }
    }

    // Exercise the real bound EditSession, including denied writes after history is reattached.
    private static void nativeHistory() {
        var level = server.overworld();
        var target = POS.offset(2, 0, 0);
        level.setBlock(target, Blocks.STONE.defaultBlockState(), 3);
        claim(actor.getUUID());
        modes().setClaimsAdminMode(false);
        modes().setClaimsNonallyMode(false);
        modes().getClaimsImpersonationInfo().reset();
        nativeReceipts.clear();
        var player = com.sk89q.worldedit.fabric.FabricAdapter.adaptPlayer(actor);
        var world = player.getWorldForEditing();
        var first = new com.fastasyncworldedit.core.history.MemoryOptimizedHistory(world);
        var second = new com.fastasyncworldedit.core.history.MemoryOptimizedHistory(world);
        CompletableFuture.runAsync(() -> {
            try (var session = com.sk89q.worldedit.WorldEdit.getInstance().newEditSessionBuilder()
                    .world(world).actor(player).forceWNA().fastMode(false).checkMemory(false)
                    .changeSet(first).build()) {
                var pos = BlockVector3.at(target.getX(), target.getY(), target.getZ());
                // A session commits at its flush; each write is flushed before the next change.
                session.setBlock(pos, BlockState.get("minecraft:gold_block"));
                session.flushQueue();
                int initial = first.size();
                session.setChangeSet(null);
                session.setBlock(pos, BlockState.get("minecraft:diamond_block"));
                session.flushQueue();
                int disabled = first.size();
                session.setChangeSet(second);
                session.setBlock(pos, BlockState.get("minecraft:dirt"));
                session.flushQueue();
                int reattached = second.size();
                com.fastasyncworldedit.core.util.TaskManager.taskManager().sync(() -> {
                    claim(OTHER);
                    return null;
                });
                session.setBlock(pos, BlockState.get("minecraft:stone"));
                session.flushQueue();
                int denied = second.size();
                Map<String, Object> facts = Map.of("case", "bound-native-history-toggle",
                        "initial", initial, "disabled", disabled, "oldFinal", first.size(),
                        "reattached", reattached, "denied", denied,
                        "applied", nativeReceipts.stream().mapToInt(r -> r.applied().size()).sum(),
                        "deniedReceipts", nativeReceipts.stream().mapToInt(CommitReceipt::denied).sum());
                server.execute(() -> results.add(facts));
                if (initial != 1 || disabled != 1 || first.size() != 1 || reattached != 1 || denied != 1) {
                    throw new AssertionError("Dynamic committed history mismatch: " + facts);
                }
                var origin = com.fastasyncworldedit.fabric.edit.NativeHistoryExtent.originOf(second);
                if (!origin.operationId().equals(nativeReceipts.get(2).context().sessionId())
                        || !origin.actorId().equals(player.getUniqueId())) {
                    throw new AssertionError("New native history lost its actual operation identity");
                }
                com.fastasyncworldedit.core.util.TaskManager.taskManager().sync(() -> {
                    claim(actor.getUUID());
                    return null;
                });
                session.disableHistory();
                session.setBlock(pos, BlockState.get("minecraft:gold_block"));
                session.flushQueue();
                if (second.size() != 1) throw new AssertionError("disableHistory kept a recorder attached");
                session.enableHistory(second);
                session.setBlock(pos, BlockState.get("minecraft:dirt"));
                session.flushQueue();
                com.fastasyncworldedit.core.util.TaskManager.taskManager().sync(() -> { claim(OTHER); return null; });
                session.setBlock(pos, BlockState.get("minecraft:stone"));
                session.flushQueue();
                if (second.size() != 2) throw new AssertionError("enableHistory created speculative/duplicate history");
                com.fastasyncworldedit.core.util.TaskManager.taskManager().sync(() -> {
                    results.add(Map.of("case", "bound-native-enable-history", "history", second.size(),
                            "deniedRecorded", false));
                    claim(actor.getUUID());
                    return null;
                });
                // Unflushed writes keep the history attached when each was made.
                var early = new com.fastasyncworldedit.core.history.MemoryOptimizedHistory(world);
                var late = new com.fastasyncworldedit.core.history.MemoryOptimizedHistory(world);
                try (var buffered = com.sk89q.worldedit.WorldEdit.getInstance().newEditSessionBuilder()
                        .world(world).actor(player).forceWNA().fastMode(false).checkMemory(false)
                        .changeSet(early).build()) {
                    buffered.setBlock(pos.add(0, 1, 0), BlockState.get("minecraft:gold_block"));
                    buffered.setChangeSet(null);
                    buffered.setBlock(pos.add(0, 2, 0), BlockState.get("minecraft:diamond_block"));
                    buffered.setChangeSet(late);
                    buffered.setBlock(pos.add(0, 3, 0), BlockState.get("minecraft:dirt"));
                }
                if (early.size() != 1 || late.size() != 1) {
                    throw new AssertionError("Unflushed writes lost their history: " + early.size() + "/" + late.size());
                }
                server.execute(() -> results.add(Map.of("case", "bound-native-history-unflushed",
                        "early", early.size(), "late", late.size())));
                var legacy = new ArrayListHistory();
                legacy.add(new com.sk89q.worldedit.history.change.BlockChange(pos,
                        BlockState.get("minecraft:stone"), BlockState.get("minecraft:dirt")));
                for (boolean redo : new boolean[]{false, true}) {
                    try (var replay = com.sk89q.worldedit.WorldEdit.getInstance().newEditSessionBuilder()
                            .world(world).actor(player).forceWNA().fastMode(false).checkMemory(false)
                            .changeSetNull().replay(legacy, redo).build()) {
                        replay.setBlocks(legacy, redo ? com.sk89q.worldedit.function.operation.ChangeSetExecutor.Type.REDO
                                : com.sk89q.worldedit.function.operation.ChangeSetExecutor.Type.UNDO);
                    }
                    var receipt = nativeReceipts.getLast();
                    if (receipt.context().parentId() != null || receipt.context().originalActorId() != null
                            || receipt.context().source() != (redo ? EditActorContext.Source.REDO : EditActorContext.Source.UNDO)
                            || !receipt.context().actorId().equals(player.getUniqueId()) || receipt.applied().size() != 1) {
                        throw new AssertionError("Legacy replay fabricated historical provenance: " + receipt);
                    }
                    server.execute(() -> results.add(Map.of("case", redo ? "legacy-redo" : "legacy-undo",
                            "source", receipt.context().source().name(), "parent", "unknown", "applied", 1)));
                }
                nativeRefusals(player, world);
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        }).whenComplete((unused, problem) -> server.execute(() -> {
            if (problem != null) {
                fail(problem);
            } else if (!level.getBlockState(target).is(Blocks.DIRT)
                    // Includes the unflushed-history case: three units, one write each.
                    || nativeReceipts.size() < 12
                    || nativeReceipts.stream().mapToInt(r -> r.applied().size()).sum() != 10
                    || nativeReceipts.stream().mapToInt(CommitReceipt::denied).sum() != 2) {
                fail(new AssertionError("Native history fixture world/receipt mismatch"));
            }
            done = true;
        }));
    }

    // Separate declared suite: action exceptions, real direct replay and native failure retention.
    private static void nativeEdges() {
        var player = com.sk89q.worldedit.fabric.FabricAdapter.adaptPlayer(actor);
        var world = player.getWorldForEditing();
        modes().setClaimsAdminMode(false);
        modes().setClaimsNonallyMode(false);
        modes().getClaimsImpersonationInfo().reset();
        CompletableFuture.runAsync(() -> {
            try {
                actionExceptions(player, world);
                directReplays(player, world);
                nativeFailure(player, world, false);
                nativeFailure(player, world, true);
                brokenProvider = true;
                try {
                    refusedNative("installed-broken-provider", "Required edit integration is unavailable", () ->
                            com.fastasyncworldedit.fabric.compat.EditIntegrations.discover());
                } finally {
                    brokenProvider = false;
                }
                refusedNative("installed-missing-provider", "Installed integration has no provider", () -> {
                    com.fastasyncworldedit.fabric.compat.EditIntegrations.validate(List.of());
                    return null;
                });
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        }).whenComplete((unused, problem) -> server.execute(() -> {
            if (problem != null) fail(problem);
            done = true;
        }));
    }

    // Distinct server-configured exceptions must not turn either half of replacement into a bypass.
    private static void actionExceptions(com.sk89q.worldedit.entity.Player player,
            com.sk89q.worldedit.world.World world) throws Exception {
        String[][] cases = {{"remove-only", "oak_planks", "air", "true"},
                {"place-only", "air", "glass", "true"},
                {"replacement-remove-only", "oak_planks", "gold_block", "false"},
                {"replacement-place-only", "stone", "glass", "false"},
                {"replacement-both", "oak_planks", "glass", "true"},
                {"replacement-neither", "stone", "gold_block", "false"}};
        for (String[] fixture : cases) {
            boolean allowed = Boolean.parseBoolean(fixture[3]);
            com.fastasyncworldedit.core.util.TaskManager.taskManager().sync(() -> {
                claim(OTHER);
                server.overworld().setBlock(POS, com.sk89q.worldedit.fabric.FabricAdapter.adapt(
                        BlockState.get("minecraft:" + fixture[1])), 3);
                return null;
            });
            nativeCase("exception-" + fixture[0], player, world, allowed ? 1 : 0, allowed ? 0 : 1,
                    allowed ? 1 : 0, false, session -> session.setBlock(BlockVector3.at(3200, 80, 3200),
                            BlockState.get("minecraft:" + fixture[2])));
            com.fastasyncworldedit.core.util.TaskManager.taskManager().sync(() -> {
                String expected = "minecraft:" + fixture[allowed ? 2 : 1];
                if (!com.sk89q.worldedit.fabric.FabricAdapter.adapt(server.overworld().getBlockState(POS))
                        .getAsString().equals(expected)) throw new AssertionError("Exception world mismatch " + fixture[0]);
                return null;
            });
        }
    }

    // Historical ownership differs from the connected operator; both directions check current claims.
    private static void directReplays(com.sk89q.worldedit.entity.Player player,
            com.sk89q.worldedit.world.World world) throws Exception {
        var historical = (com.sk89q.worldedit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(
                ClaimsProbe.class.getClassLoader(), new Class<?>[]{com.sk89q.worldedit.entity.Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> OTHER;
                    case "getName" -> "s6historyowner";
                    default -> method.invoke(player, args);
                });
        var pos = BlockVector3.at(3200, 80, 3200);
        for (String api : List.of("session", "setBlocks", "disk")) {
            for (boolean redo : new boolean[]{false, true}) {
                for (boolean allowed : new boolean[]{false, true}) {
                    com.fastasyncworldedit.core.util.TaskManager.taskManager().sync(() -> {
                        claim(allowed ? actor.getUUID() : OTHER);
                        server.overworld().setBlock(POS, (redo ? Blocks.STONE : Blocks.GOLD_BLOCK).defaultBlockState(), 3);
                        return null;
                    });
                    nativeReceipts.clear();
                    var history = api.equals("disk")
                            ? new com.fastasyncworldedit.core.history.DiskStorageHistory(world, OTHER)
                            : new com.fastasyncworldedit.core.history.MemoryOptimizedHistory(world);
                    history.add(pos.x(), pos.y(), pos.z(), BlockState.get("minecraft:stone").toBaseBlock(),
                            BlockState.get("minecraft:gold_block").toBaseBlock());
                    try (var source = com.sk89q.worldedit.WorldEdit.getInstance().newEditSessionBuilder()
                            .world(world).actor(historical).forceWNA().fastMode(false).checkMemory(false)
                            .changeSet(history).build();
                         var destination = com.sk89q.worldedit.WorldEdit.getInstance().newEditSessionBuilder()
                            .world(world).actor(player).forceWNA().fastMode(false).checkMemory(false).changeSetNull().build()) {
                        if (api.equals("session")) {
                            if (redo) source.redo(destination); else source.undo(destination);
                        } else if (api.equals("setBlocks")) {
                            destination.setBlocks(history, redo
                                    ? com.sk89q.worldedit.function.operation.ChangeSetExecutor.Type.REDO
                                    : com.sk89q.worldedit.function.operation.ChangeSetExecutor.Type.UNDO);
                        } else {
                            var disk = (com.fastasyncworldedit.core.history.DiskStorageHistory) history;
                            if (redo) disk.redo(player); else disk.undo(player);
                        }
                    }
                    if (nativeReceipts.size() != 1 || nativeReceipts.getFirst().applied().size() != (allowed ? 1 : 0)
                            || nativeReceipts.getFirst().denied() != (allowed ? 0 : 1)
                            || !nativeReceipts.getFirst().context().actorId().equals(player.getUniqueId())
                            || history.size() != 1) throw new AssertionError("Direct replay authority: " + api + nativeReceipts);
                    com.fastasyncworldedit.core.util.TaskManager.taskManager().sync(() -> {
                        var expected = (allowed == redo) ? Blocks.GOLD_BLOCK : Blocks.STONE;
                        if (!server.overworld().getBlockState(POS).is(expected)) {
                            throw new AssertionError("Direct replay world: " + api);
                        }
                        results.add(Map.of("case", "direct-" + api + (redo ? "-redo" : "-undo")
                                + (allowed ? "-allowed" : "-denied"), "operator", actor.getUUID(),
                                "historicalActor", OTHER, "applied", allowed ? 1 : 0, "history", history.size(),
                                "sourceLimitation", nativeReceipts.getFirst().context().source().name()));
                        return null;
                    });
                }
            }
        }
    }

    // Inject at the real native writer or required provider finalizer, never at a fake coordinator.
    private static void nativeFailure(com.sk89q.worldedit.entity.Player player,
            com.sk89q.worldedit.world.World world, boolean finalizer) throws Exception {
        int x = finalizer ? 3248 : 3232;
        var pos = BlockVector3.at(x, 80, 3200);
        com.fastasyncworldedit.core.util.TaskManager.taskManager().sync(() -> {
            var level = server.overworld();
            var claims = OpenPACServerAPI.get(server).getServerClaimsManager();
            claims.unclaim(level.dimension().identifier(), x >> 4, 200);
            if (claims.claim(level.dimension().identifier(), actor.getUUID(), 0, x >> 4, 200, false) == null) {
                throw new AssertionError("Failure fixture claim");
            }
            level.setBlock(new BlockPos(x, 80, 3200), Blocks.STONE.defaultBlockState(), 3);
            return null;
        });
        nativeReceipts.clear();
        var history = new com.fastasyncworldedit.core.history.MemoryOptimizedHistory(world);
        finalizerCalls = 0;
        mutationCalls = 0;
        mutationFault = !finalizer;
        finalizerFault = finalizer;
        com.fastasyncworldedit.fabric.edit.FabricCommitCoordinator.CommitFailure observed = null;
        try (var session = com.sk89q.worldedit.WorldEdit.getInstance().newEditSessionBuilder()
                .world(world).actor(player).forceWNA().fastMode(false).checkMemory(false).changeSet(history).build()) {
            session.setBlock(pos, BlockState.get("minecraft:gold_block"));
        } catch (Exception failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof com.fastasyncworldedit.fabric.edit.FabricCommitCoordinator.CommitFailure commit) {
                    observed = commit;
                    break;
                }
            }
            if (observed == null) throw failure;
        } finally {
            mutationFault = false;
            finalizerFault = false;
        }
        if (observed == null || observed.receipt().applied().size() != 1 || history.size() != 1
                || nativeReceipts.size() != 1 || nativeReceipts.getFirst().applied().size() != 1
                || (finalizer ? finalizerCalls : mutationCalls) != 1) {
            throw new AssertionError("Native failure lost actual effects: " + finalizer + " " + nativeReceipts);
        }
        com.fastasyncworldedit.core.util.TaskManager.taskManager().sync(() -> {
            if (!server.overworld().getBlockState(new BlockPos(x, 80, 3200)).is(Blocks.GOLD_BLOCK)) {
                throw new AssertionError("Native failed mutation disappeared");
            }
            results.add(Map.of("case", finalizer ? "native-finalizer-failure" : "native-mutation-then-throw",
                    "completionFailed", true, "world", "minecraft:gold_block", "history", history.size(),
                    "appliedReceipt", 1, "faultCalls", finalizer ? finalizerCalls : mutationCalls));
            return null;
        });
    }

    // Two adjacent real claims exercise independent clipping and sensitive native container writes.
    private static void nativeBorders() {
        var level = server.overworld();
        claim(actor.getUUID());
        var api = OpenPACServerAPI.get(server);
        var dimension = level.dimension().identifier();
        api.getServerClaimsManager().unclaim(dimension, 201, 200);
        var foreign = api.getServerClaimsManager().claim(dimension, OTHER, 0, 201, 200, false);
        if (foreign == null) throw new AssertionError("Adjacent claim fixture failed");
        var config = api.getChunkProtection().getConfig(foreign, dimension);
        config.tryToSet(PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS, true);
        config.tryToSet(PlayerConfigOptions.FULL_ACCESS, "P");
        if (!config.getEffective(PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS)
                || !config.getEffective(PlayerConfigOptions.FULL_ACCESS).equals("P")) {
            throw new AssertionError("Adjacent claim policy did not take effect");
        }
        for (int x : new int[]{3215, 3216}) level.setBlock(new BlockPos(x, 80, 3200), Blocks.STONE.defaultBlockState(), 3);
        for (int x : new int[]{3208, 3224}) {
            var pos = new BlockPos(x, 80, 3200);
            level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
            ((net.minecraft.world.Container) level.getBlockEntity(pos)).setItem(0,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 7));
        }
        var player = com.sk89q.worldedit.fabric.FabricAdapter.adaptPlayer(actor);
        var world = player.getWorldForEditing();
        CompletableFuture.runAsync(() -> {
            try {
                nativeCase("border-native-fill", player, world, 1, 1, 1, false, session -> session.setBlocks(
                        (com.sk89q.worldedit.regions.Region) new com.sk89q.worldedit.regions.CuboidRegion(
                                BlockVector3.at(3215, 80, 3200),
                                BlockVector3.at(3216, 80, 3200)), BlockState.get("minecraft:gold_block")));
                nativeCase("border-native-biome", player, world, 1, 1, 1, false, session -> {
                    session.setBiome(BlockVector3.at(3212, 80, 3200), BiomeTypes.DESERT);
                    session.setBiome(BlockVector3.at(3216, 80, 3200), BiomeTypes.DESERT);
                });
                nativeCase("native-clear-owner", player, world, 1, 0, 3, false, session ->
                        session.getWorld().clearContainerBlockContents(BlockVector3.at(3208, 80, 3200)));
                nativeCase("native-clear-stranger", player, world, 0, 0, 0, true, session ->
                        session.getWorld().clearContainerBlockContents(BlockVector3.at(3224, 80, 3200)));
                nativeCase("native-nbt-owner", player, world, 1, 0, 3, false, session -> {
                    CompoundTag tag = new CompoundTag();
                    tag.putString("id", "minecraft:chest");
                    tag.putString("CustomName", "\"s6-allowed\"");
                    session.setBlock(BlockVector3.at(3208, 80, 3200), BlockState.get(
                            "minecraft:chest[facing=north,type=single,waterlogged=false]")
                            .toBaseBlock(NBTConverter.fromNative(tag)));
                });
                nativeCase("native-nbt-stranger", player, world, 0, 0, 0, true, session -> {
                    CompoundTag tag = new CompoundTag();
                    tag.putString("id", "minecraft:chest");
                    tag.putString("CustomName", "\"s6-denied\"");
                    session.setBlock(BlockVector3.at(3224, 80, 3200), BlockState.get(
                            "minecraft:chest[facing=north,type=single,waterlogged=false]")
                            .toBaseBlock(NBTConverter.fromNative(tag)));
                });
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        }).whenComplete((unused, problem) -> server.execute(() -> {
            try {
                if (problem != null) throw new AssertionError("Native border fixture", problem);
                verifyBorderWorld(level, Blocks.GOLD_BLOCK);
            } catch (Throwable error) {
                fail(error);
            }
            done = true;
        }));
    }

    // Read stored quart cells, not BiomeManager's neighboring-cell Voronoi sample.
    private static void verifyBorderWorld(ServerLevel level, net.minecraft.world.level.block.Block owned) {
        if (!level.getBlockState(new BlockPos(3215, 80, 3200)).is(owned)
                || !level.getBlockState(new BlockPos(3216, 80, 3200)).is(Blocks.STONE)
                || !level.getChunk(200, 200).getNoiseBiome(803, 20, 800).is(net.minecraft.world.level.biome.Biomes.DESERT)
                || !level.getChunk(201, 200).getNoiseBiome(804, 20, 800).is(net.minecraft.world.level.biome.Biomes.PLAINS)
                || !((net.minecraft.world.Container) level.getBlockEntity(new BlockPos(3208, 80, 3200))).isEmpty()
                || !level.getBlockEntity(new BlockPos(3208, 80, 3200)).saveWithFullMetadata(level.registryAccess())
                        .toString().contains("s6-allowed")
                || level.getBlockEntity(new BlockPos(3224, 80, 3200)).saveWithFullMetadata(level.registryAccess())
                        .toString().contains("s6-denied")
                || ((net.minecraft.world.Container) level.getBlockEntity(new BlockPos(3224, 80, 3200)))
                        .getItem(0).getCount() != 7) {
            throw new AssertionError("Native border world/inventory mismatch");
        }
    }

    @FunctionalInterface
    private interface NativeAction {
        void run(com.sk89q.worldedit.EditSession session) throws Exception;
    }

    // Compare the actual receipt observer and history, including refusal before snapshot exposure.
    private static void nativeCase(String name, com.sk89q.worldedit.entity.Player player,
            com.sk89q.worldedit.world.World world, int applied, int denied, int historyEntries, boolean refusedRead,
            NativeAction action) throws Exception {
        nativeReceipts.clear();
        var history = new com.fastasyncworldedit.core.history.MemoryOptimizedHistory(world);
        boolean refused = false;
        try (var session = com.sk89q.worldedit.WorldEdit.getInstance().newEditSessionBuilder()
                .world(world).actor(player).forceWNA().fastMode(false).checkMemory(false).changeSet(history).build()) {
            action.run(session);
        } catch (Exception failure) {
            Throwable cause = failure;
            while (cause != null && !(cause instanceof com.fastasyncworldedit.fabric.compat.NativeClaims.RefusedRead)) {
                cause = cause.getCause();
            }
            if (cause == null || !refusedRead) throw failure;
            refused = true;
        }
        Map<String, Object> facts = Map.of("case", name,
                "applied", nativeReceipts.stream().mapToInt(receipt -> receipt.applied().size()).sum(),
                "denied", nativeReceipts.stream().mapToInt(CommitReceipt::denied).sum(),
                "history", history.size(), "refusedBeforeExposure", refused);
        com.fastasyncworldedit.core.util.TaskManager.taskManager().sync(() -> { results.add(facts); return null; });
        if (refused != refusedRead || history.size() != historyEntries
                || nativeReceipts.stream().mapToInt(receipt -> receipt.applied().size()).sum() != applied
                || nativeReceipts.stream().mapToInt(CommitReceipt::denied).sum() != denied
                || refusedRead && !nativeReceipts.isEmpty()) {
            throw new AssertionError(name + " history/receipt/refusal mismatch: " + facts);
        }
    }

    // Assert explicit production entry-point refusal, not a skipped entity/compound test.
    private static void nativeRefusals(com.sk89q.worldedit.entity.Player player,
            com.sk89q.worldedit.world.World world) throws Exception {
        var history = new com.fastasyncworldedit.core.history.MemoryOptimizedHistory(world);
        try (var session = com.sk89q.worldedit.WorldEdit.getInstance().newEditSessionBuilder()
                .world(world).actor(player).forceWNA().fastMode(false).checkMemory(false).changeSet(history).build()) {
            var pos = BlockVector3.at(3202, 80, 3200);
            var region = new com.sk89q.worldedit.regions.CuboidRegion(pos, pos);
            int before = nativeReceipts.size();
            try {
                refusedNative("native-move-no-entities", "move/regeneration", () ->
                        session.moveRegion(region, BlockVector3.at(1, 0, 0), 1, false, false,
                                (com.sk89q.worldedit.function.mask.Mask) null, null));
            } finally {
                com.fastasyncworldedit.core.util.TaskManager.taskManager().sync(() -> {
                    results.add(Map.of("case", "native-move-world-observation",
                            "source", server.overworld().getBlockState(new BlockPos(3202, 80, 3200)).toString(),
                            "destination", server.overworld().getBlockState(new BlockPos(3203, 80, 3200)).toString()));
                    return null;
                });
            }
            refusedNative("native-move", "move/regeneration", () ->
                    session.moveRegion(region, BlockVector3.at(1, 0, 0), 1, true, null));
            refusedNative("native-regeneration", "unsupported operation", () -> session.regenerate(region));
            refusedNative("native-entity-create", "unsupported operation", () -> session.getWorld().createEntity(
                    new com.sk89q.worldedit.util.Location(session.getWorld(), pos.toVector3()),
                    new com.sk89q.worldedit.entity.BaseEntity(com.sk89q.worldedit.world.entity.EntityTypes.PIG)));
            if (nativeReceipts.size() != before || history.size() != 0) {
                throw new AssertionError("Unsupported native operation reached mutation/history");
            }
            // A cell without lifecycle support is skipped and reported at flush, never written.
            if (session.setBlock(pos, BlockState.get("minecraft:beacon"))) {
                throw new AssertionError("Unknown native lifecycle reported a change");
            }
            server.execute(() -> results.add(Map.of("case", "native-unknown-lifecycle", "outcome", "skipped")));
            if (history.size() != 0 || nativeReceipts.subList(before, nativeReceipts.size()).stream()
                    .anyMatch(receipt -> !receipt.applied().isEmpty())) {
                throw new AssertionError("Unknown native lifecycle changed history or receipts");
            }
        }
    }

    // Match the actual refusal; any other exception remains a failing fixture.
    private static void refusedNative(String name, String message, java.util.concurrent.Callable<?> action) throws Exception {
        try {
            action.call();
        } catch (Exception failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause.getMessage() != null && cause.getMessage().contains(message)) {
                    server.execute(() -> results.add(Map.of("case", name, "outcome", "explicit-refusal",
                            "reason", failure.toString())));
                    return;
                }
            }
            throw failure;
        }
        throw new AssertionError("Expected explicit refusal: " + name);
    }

    // Unsafe semantics must fail before loading, callback execution, source removal or admission.
    private static void refuseCompounds(EditActorContext context, ServerLevel level) {
        FabricEdits service = FabricEdits.get(server);
        for (FabricEdits.Semantics semantics : FabricEdits.Semantics.values()) {
            if (semantics == FabricEdits.Semantics.INDEPENDENT) {
                continue;
            }
            var history = new ArrayListHistory();
            var entered = new java.util.concurrent.atomic.AtomicBoolean();
            var stats = service.preparationStatistics();
            int pending = service.pendingOperations();
            boolean refused = false;
            try {
                service.submitUnits(context, List.of(new FabricEdits.Unit(List.of(BlockVector2.at(200, 200)),
                        queue -> {
                            entered.set(true);
                            queue.setBlock(3200, 80, 3200, BlockState.get("minecraft:air"));
                            queue.setBlock(3201, 80, 3200, BlockState.get("minecraft:stone"));
                        }, semantics)), history);
            } catch (UnsupportedOperationException expected) {
                refused = expected.getMessage().contains("No compound publication contract");
            }
            if (!refused || entered.get() || history.size() != 0 || service.pendingOperations() != pending
                    || !stats.equals(service.preparationStatistics()) || !level.getBlockState(POS).is(Blocks.STONE)) {
                throw new AssertionError("Compound admission did not refuse before changes: " + semantics);
            }
            results.add(Map.of("case", "contract-" + semantics, "outcome", "refused-before-admission",
                    "callbackRan", false, "history", 0));
        }
    }

    // Disconnect the real offline fixture connection after preparation and before publication.
    private static void disconnect() {
        disconnectDone = false;
        override = null;
        var level = server.overworld();
        var target = POS.offset(0, 0, 1);
        level.setBlock(target, Blocks.STONE.defaultBlockState(), 3);
        claim(actor.getUUID());
        modes().setClaimsAdminMode(false);
        var context = new EditActorContext(actor.getUUID(), actor.getGameProfile().name(), EditActorContext.Source.API,
                UUID.randomUUID(), null, null, level.dimension().identifier().toString(), SideEffectSet.none(),
                new EditActorContext.Cancellation());
        var history = new ArrayListHistory();
        recorded = 0;
        race = () -> actor.connection.disconnect(Component.literal("S6 synthetic disconnect fixture"));
        FabricEdits.get(server).submitIndependent(context, List.of(BlockVector2.at(200, 200)), history,
                queue -> queue.setBlock(target.getX(), target.getY(), target.getZ(), BlockState.get("minecraft:diamond_block")))
                .whenComplete((receipts, problem) -> server.execute(() -> {
                    try {
                        Throwable cause = problem;
                        while (cause != null && !(cause instanceof FabricEdits.OperationFailure)) {
                            cause = cause.getCause();
                        }
                        if (!(cause instanceof FabricEdits.OperationFailure failed) || !actor.hasDisconnected()
                                || history.size() != 0 || recorded != 0 || !level.getBlockState(target).is(Blocks.STONE)
                                || failed.publishedReceipts().stream().anyMatch(receipt -> !receipt.applied().isEmpty())) {
                            throw new AssertionError("Disconnected actor changed the queued fixture", problem);
                        }
                        results.add(Map.of("case", "actual-disconnect", "outcome", "failed-before-write",
                                "history", 0, "receiptObserver", 0, "world", "minecraft:stone"));
                    } catch (Throwable error) {
                        fail(error);
                    } finally {
                        disconnectDone = true;
                    }
                }));
    }

    // Fixture mutations use real OPAC services, never a stand-in authorizer.
    private static void claim(UUID owner) {
        var api = OpenPACServerAPI.get(server);
        var dimension = server.overworld().dimension().identifier();
        api.getServerClaimsManager().unclaim(dimension, 200, 200);
        if (api.getServerClaimsManager().claim(dimension, owner, 0, 200, 200, false) == null) {
            throw new AssertionError("Claim fixture failed");
        }
        setProtected(true);
        setOption(PlayerConfigOptions.FULL_ACCESS, "P");
    }

    // Require fixture changes to be effective; never substitute a weaker configuration on failure.
    private static <T> void setOption(xaero.pac.common.server.player.config.api.v2.IPlayerConfigOptionSpecAPI<T> option,
            T value) {
        config().tryToSet(option, value);
        if (!java.util.Objects.equals(config().getEffective(option), value)) {
            throw new AssertionError("OPAC fixture option did not take effect: " + option);
        }
    }

    // Resolve the exact claim's effective config, including offline-owner configurations.
    private static IPlayerConfigAPI config() {
        var api = OpenPACServerAPI.get(server);
        var dim = server.overworld().dimension().identifier();
        return api.getChunkProtection().getConfig(api.getServerClaimsManager().get(dim, 200, 200), dim);
    }

    // Configuration is checked after mutation so an invalid fixture cannot masquerade as denial.
    private static void setProtected(boolean value) {
        config().tryToSet(PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS, value);
        if (config().getEffective(PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS) != value) {
            throw new AssertionError("Protection config did not change");
        }
    }

    // OPAC exposes mode setters on its real player data implementation for test setup.
    private static ServerPlayerData modes() {
        return (ServerPlayerData) ServerPlayerDataAPI.from(actor);
    }

    // Keep the first failure for the external runner; never continue into a fresh success result.
    private static void fail(Throwable error) {
        failure = error.toString();
        error.printStackTrace();
        done = true;
    }
}
