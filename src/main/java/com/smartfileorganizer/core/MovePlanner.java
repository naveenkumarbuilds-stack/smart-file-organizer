package com.smartfileorganizer.core;

import com.smartfileorganizer.model.PlannedMove;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds immutable previews from snapshots; performs no file-system operations. */
public final class MovePlanner {
    private final CategoryClassifier classifier = new CategoryClassifier();

    /** Creates a planner using the default classifier. */
    public MovePlanner() {
    }

    /**
     * Plans direct-child sources, reserving existing and planned destinations.
     * Case-insensitive reservation conservatively protects Windows filenames.
     * @param folder validated source folder
     * @param files eligible source paths from the scanner
     * @param occupied existing destination paths from the scanner
     * @return deterministic immutable moves
     */
    public List<PlannedMove> plan(Path folder, List<Path> files, Set<Path> occupied) {
        Path root = folder.toAbsolutePath().normalize();
        var reserved = new HashSet<String>();
        occupied.forEach(path -> reserved.add(key(path)));
        var seen = new HashSet<String>();
        var moves = new ArrayList<PlannedMove>();
        for (Path file : files.stream().sorted(Comparator.comparing(Path::toString)).toList()) {
            Path source = file.toAbsolutePath().normalize();
            if (!root.equals(source.getParent()) || !seen.add(key(source))) {
                throw new IllegalArgumentException("Sources must be unique direct children of the selected folder.");
            }
            var category = classifier.classify(source);
            String name = source.getFileName().toString();
            int dot = name.lastIndexOf('.');
            boolean hasExtension = dot > 0 && dot < name.length() - 1;
            String stem = hasExtension ? name.substring(0, dot) : name;
            String extension = hasExtension ? name.substring(dot) : "";
            Path targetFolder = root.resolve(category.folderName());
            Path target = targetFolder.resolve(name);
            int suffix = 1;
            while (!reserved.add(key(target))) {
                target = targetFolder.resolve(stem + " (" + suffix++ + ")" + extension);
            }
            moves.add(new PlannedMove(source, target, category));
        }
        return List.copyOf(moves);
    }

    private static String key(Path path) {
        return path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }
}
