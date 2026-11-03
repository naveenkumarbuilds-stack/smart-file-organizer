package com.smartfileorganizer.core;

import com.smartfileorganizer.model.FileCategory;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Classifies names by their final extension without accessing the file system. */
public final class CategoryClassifier {
    /** Creates a classifier using the default category mapping. */
    public CategoryClassifier() {
    }

    /**
     * Classifies a path's filename, ignoring extension case.
     * Names without an extension, dotfiles, and unknown types go to Others.
     * @param path non-null path; the file does not need to exist
     * @return the matching category, or Others
     */
    public FileCategory classify(Path path) {
        Objects.requireNonNull(path, "path");
        Path filename = path.getFileName();
        if (filename == null) {
            return FileCategory.OTHERS;
        }
        String name = filename.toString();
        int dot = name.lastIndexOf('.');
        // Leading dots mark dotfiles rather than a usable extension.
        if (name.startsWith(".") || dot < 0 || dot == name.length() - 1) {
            return FileCategory.OTHERS;
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (FileCategory category : FileCategory.values()) {
            if (category.extensions().contains(extension)) {
                return category;
            }
        }
        return FileCategory.OTHERS;
    }
}
