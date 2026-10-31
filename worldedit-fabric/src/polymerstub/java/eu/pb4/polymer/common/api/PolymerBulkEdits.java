package eu.pb4.polymer.common.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Compile-time mirror of Polymer's candidate bulk-edit API.
 *
 * <p>The real class is supplied at runtime by polymer-common. This source set is never packaged
 * into the FAWE jar and is not on the runtime classpath. Keep the signatures in sync with
 * {@code handoff/s7-polymer/api-contract.json}; the bridge calls only the public API.</p>
 */
public final class PolymerBulkEdits {
    public static final String ENTRYPOINT = "polymer:bulk_edits";

    private PolymerBulkEdits() {
    }

    public static Token prepare(LevelChunk chunk, Set<Integer> sections,
            Map<BlockPos, BlockState> proposed) {
        throw new UnsupportedOperationException("Compile-time stub");
    }

    /** Immutable cell delta with copied NBT accessors. */
    public record Change(BlockPos pos, BlockState before, BlockState after,
                         CompoundTag beforeNbt, CompoundTag afterNbt) {
        public boolean nbtChanged() {
            throw new UnsupportedOperationException("Compile-time stub");
        }
    }

    /** Single-use loaded-chunk token. */
    public static final class Token implements AutoCloseable {
        private Token() {
        }

        public List<Change> reconcile() {
            throw new UnsupportedOperationException("Compile-time stub");
        }

        public void publish() {
            throw new UnsupportedOperationException("Compile-time stub");
        }

        public List<Change> changes() {
            throw new UnsupportedOperationException("Compile-time stub");
        }

        @Override
        public void close() {
            throw new UnsupportedOperationException("Compile-time stub");
        }
    }
}
