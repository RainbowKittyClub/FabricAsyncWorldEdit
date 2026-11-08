package com.fastasyncworldedit.testharness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fastasyncworldedit.core.history.change.MutableBlockChange;
import com.fastasyncworldedit.core.history.change.MutableFullBlockChange;
import com.fastasyncworldedit.core.history.change.MutableTileChange;
import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.fastasyncworldedit.fabric.edit.NativeHistoryExtent;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.NullExtent;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.history.UndoContext;
import com.sk89q.worldedit.history.change.BlockChange;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockStateHolder;

import net.minecraft.server.MinecraftServer;

/** Projects durable history into an inert extent; never replays the observed edit into a world. */
final class HistoryCellsProbe {
    private HistoryCellsProbe() { }

    static Object run(MinecraftServer server, String name) {
        var player = server.getPlayerList().getPlayerByName(name);
        if (player == null) throw new IllegalArgumentException("No such player " + name);
        var actor = FabricAdapter.adaptPlayer(player);
        var session = WorldEdit.getInstance().getSessionManager().get(actor);
        session.loadSessionHistoryFromDisk(actor.getUniqueId(), actor.getWorld());
        if (session.getHistory().isEmpty()) return Map.of("ready", false);
        ChangeSet history = session.getHistory().getLast();
        return Map.of("ready", true, "rawSize", history.longSize(),
                "operation", String.valueOf(NativeHistoryExtent.originOf(history).operationId()),
                "undo", project(history, false), "redo", project(history, true));
    }

    // Exhausting the real iterator also exercises the stored disk records, not receipt counters.
    private static Object project(ChangeSet history, boolean redo) {
        Capture extent = new Capture();
        UndoContext context = new UndoContext();
        context.setExtent(extent);
        var changes = redo ? history.forwardIterator() : history.backwardIterator();
        List<String> unexpected = new ArrayList<>();
        int records = 0;
        while (changes.hasNext()) {
            if (++records > 8192) throw new IllegalArgumentException("History projection exceeds fixture bound");
            var change = changes.next();
            if (!(change instanceof MutableBlockChange || change instanceof MutableFullBlockChange
                    || change instanceof MutableTileChange || change instanceof BlockChange)) {
                unexpected.add(change.getClass().getName());
                continue;
            }
            try {
                if (redo) change.redo(context);
                else change.undo(context);
            } catch (WorldEditException failure) {
                throw new IllegalStateException(failure);
            }
        }
        return Map.of("records", records, "blocks", extent.blocks, "tiles", extent.tiles,
                "unexpected", unexpected);
    }

    // Mutable iterators reuse their objects, so record detached coordinates and state strings now.
    private static final class Capture extends NullExtent {
        private final List<Map<String, Object>> blocks = new ArrayList<>();
        private final List<String> tiles = new ArrayList<>();

        @Override
        public <B extends BlockStateHolder<B>> boolean setBlock(BlockVector3 position, B block) {
            return setBlock(position.x(), position.y(), position.z(), block);
        }

        @Override
        public <B extends BlockStateHolder<B>> boolean setBlock(int x, int y, int z, B block) {
            String cell = x + "," + y + "," + z;
            blocks.add(Map.of("cell", cell, "state", block.toImmutableState().getAsString()));
            if (block.toBaseBlock().getNbt() != null) tiles.add(cell);
            return true;
        }

        @Override
        public boolean tile(int x, int y, int z, FaweCompoundTag tile) {
            tiles.add(x + "," + y + "," + z);
            return true;
        }
    }
}
