package com.fastasyncworldedit.fabric.compat.ledger;

import java.time.Instant;

import com.fastasyncworldedit.fabric.edit.EditActorContext;
import com.fastasyncworldedit.fabric.edit.EditDelta;
import com.fastasyncworldedit.fabric.edit.EditValue;
import com.github.quiltservertools.ledger.actions.AbstractActionType;
import com.google.gson.JsonObject;
import com.sk89q.worldedit.fabric.FabricAdapter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One FAWE delta as one Ledger row. The state columns follow Ledger's own convention so search and
 * inspection read them; the extra data keeps both full sides, NBT included, for exact replay.
 */
final class LedgerPayloads {
    static final String SOURCE = "fawe";
    static final String FORMAT = "fawe-ledger-v1";

    private LedgerPayloads() {
    }

    static AbstractActionType action(EditActorContext context, long sequence, EditDelta delta, Instant timestamp) {
        AbstractActionType action = switch (delta.kind()) {
            case BLOCK -> block(delta);
            case BIOME -> biome(delta);
            default -> throw new IllegalArgumentException("Ledger bridge does not record " + delta.kind());
        };
        action.setPos(new BlockPos(delta.x(), delta.y(), delta.z()));
        action.setWorld(Identifier.parse(context.dimension()));
        action.setTimestamp(timestamp);
        action.setSourceName(SOURCE);
        if (context.actorId() != null && context.actorName() != null) {
            action.setSourceProfile(new NameAndId(context.actorId(), context.actorName()));
        }
        action.setExtraData(extra(context, sequence, delta));
        return action;
    }

    private static AbstractActionType block(EditDelta delta) {
        var action = new FaweLedgerActions.Block();
        BlockState before = state(delta.before());
        BlockState after = state(delta.after());
        action.setOldObjectIdentifier(BuiltInRegistries.BLOCK.getKey(before.getBlock()));
        action.setObjectIdentifier(BuiltInRegistries.BLOCK.getKey(after.getBlock()));
        action.setOldObjectState(properties(before));
        action.setObjectState(properties(after));
        return action;
    }

    private static AbstractActionType biome(EditDelta delta) {
        var action = new FaweLedgerActions.Biome();
        action.setOldObjectIdentifier(Identifier.parse(delta.before().id()));
        action.setObjectIdentifier(Identifier.parse(delta.after().id()));
        return action;
    }

    private static BlockState state(EditValue value) {
        return FabricAdapter.adapt(com.sk89q.worldedit.world.block.BlockState.get(value.id()));
    }

    // Ledger's own encoding: null for a block's default state, otherwise its property compound.
    private static String properties(BlockState state) {
        CompoundTag tag = com.github.quiltservertools.ledger.utility.NbtUtils.INSTANCE.blockStateToProperties(state);
        return tag == null ? null : tag.toString();
    }

    private static String extra(EditActorContext context, long sequence, EditDelta delta) {
        JsonObject extra = new JsonObject();
        extra.addProperty("format", FORMAT);
        extra.addProperty("kind", delta.kind().name());
        extra.addProperty("operation", context.operationId().toString());
        extra.addProperty("parent", context.parentId() == null ? null : context.parentId().toString());
        extra.addProperty("sequence", sequence);
        extra.addProperty("source", context.source().name());
        extra.add("before", side(delta.before()));
        extra.add("after", side(delta.after()));
        return extra.toString();
    }

    private static JsonObject side(EditValue value) {
        JsonObject side = new JsonObject();
        side.addProperty("id", value.id());
        side.addProperty("nbt", value.nbt() == null ? null : value.nbt().encoded());
        return side;
    }
}
