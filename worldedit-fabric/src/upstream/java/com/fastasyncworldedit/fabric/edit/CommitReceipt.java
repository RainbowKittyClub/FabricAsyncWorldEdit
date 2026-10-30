package com.fastasyncworldedit.fabric.edit;

import java.util.List;
import java.util.Objects;

/**
 * Immutable facts about one bounded publication; finalization failure never retracts applied facts.
 *
 * @param context current authority and provenance
 * @param chunkX destination chunk x
 * @param chunkZ destination chunk z
 * @param applied actual changes, never requested/denied/no-op writes
 * @param denied rejected proposed deltas
 * @param noOp proposals already equal to current state
 * @param outcome terminal result of publication/finalization
 * @param failure diagnostic, empty on success
 */
public record CommitReceipt(EditActorContext context, int chunkX, int chunkZ,
        List<AppliedDelta> applied, int denied, int noOp, Outcome outcome, String failure) {
    public CommitReceipt {
        Objects.requireNonNull(context);
        applied = List.copyOf(applied);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(failure);
        if (denied < 0 || noOp < 0) {
            throw new IllegalArgumentException("Receipt counts cannot be negative");
        }
        long previous = -1;
        for (AppliedDelta delta : applied) {
            if (delta.sequence() <= previous || delta.delta().isNoOp()) {
                throw new IllegalArgumentException("Receipt requires ordered, non-no-op applied deltas");
            }
            previous = delta.sequence();
        }
    }

    /** Terminal states distinguish publication facts from notification success. */
    public enum Outcome {
        COMPLETE, CANCELLED_BEFORE_WRITE, PARTIAL, FAILED_BEFORE_WRITE, CONFLICT,
        APPLIED_BUT_FINALIZATION_FAILED
    }

    /**
     * Stable audit/history identity within the operation.
     *
     * @param sequence unique monotonically increasing operation-local sequence
     * @param delta actual immediately-before and immediately-after values
     */
    public record AppliedDelta(long sequence, EditDelta delta) {
        public AppliedDelta {
            Objects.requireNonNull(delta);
            if (sequence < 0) {
                throw new IllegalArgumentException("Sequence must be nonnegative");
            }
        }
    }
}
