package com.smartfileorganizer.model;

/**
 * Counts the outcomes of an organization batch.
 * @param moved number of successful moves
 * @param skipped number of files skipped
 * @param failed number of attempted moves that failed
 */
public record OrganizeResult(int moved, int skipped, int failed) {
    /** Creates a summary; negative counts are rejected. */
    public OrganizeResult {
        if (moved < 0 || skipped < 0 || failed < 0) {
            throw new IllegalArgumentException("Result counts cannot be negative.");
        }
    }
}
