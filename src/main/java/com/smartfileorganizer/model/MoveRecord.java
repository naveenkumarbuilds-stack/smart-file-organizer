package com.smartfileorganizer.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Records a completed move for later undo.
 * @param originalPath path before the move
 * @param newPath path after the move
 * @param timestamp time the move completed
 */
public record MoveRecord(Path originalPath, Path newPath, Instant timestamp) {
    /** Creates a record with non-null paths and timestamp. */
    public MoveRecord {
        Objects.requireNonNull(originalPath, "originalPath");
        Objects.requireNonNull(newPath, "newPath");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
