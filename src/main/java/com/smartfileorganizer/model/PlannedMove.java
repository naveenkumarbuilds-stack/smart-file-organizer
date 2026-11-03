package com.smartfileorganizer.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A proposed move; creating this value does not touch the file system.
 * @param source current file path
 * @param destination proposed destination path
 * @param category file classification
 */
public record PlannedMove(Path source, Path destination, FileCategory category) {
    /** Creates a move with non-null paths and category. */
    public PlannedMove {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(category, "category");
    }
}
