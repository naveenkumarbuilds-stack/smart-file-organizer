package com.smartfileorganizer.core;

import com.smartfileorganizer.model.FileCategory;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Collects eligible direct-child files and destination names for a preview. */
public final class FolderScanner {
    private static final Logger LOG = Logger.getLogger(FolderScanner.class.getName());
    private final SafetyChecker safety = new SafetyChecker();
    private static final Set<String> TEMP_EXTENSIONS = Set.of("crdownload", "part", "tmp");

    /** Creates a scanner with the default safety policy. */
    public FolderScanner() {
    }

    /**
     * Immutable scan snapshot. Skipped entries include reasons for the UI.
     * @param folder canonical source directory
     * @param files eligible sources
     * @param occupied existing names in category folders
     * @param skipped reasons for excluded entries
     */
    public record ScanResult(Path folder, List<Path> files, Set<Path> occupied, List<String> skipped) {
        /** Defensively copies the snapshot collections. */
        public ScanResult {
            files = List.copyOf(files);
            occupied = Set.copyOf(occupied);
            skipped = List.copyOf(skipped);
        }
    }

    /**
     * Scans without moving files or creating category folders.
     * Category directory names are read only to reserve occupied destinations;
     * their contents never become source files and are not scanned recursively.
     * @param folder selected folder
     * @param undoLog exact app undo-log path to exclude
     * @return immutable snapshot for the pure planner
     * @throws IOException if the source or a destination cannot be inspected safely
     */
    public ScanResult scan(Path folder, Path undoLog) throws IOException {
        Path root = safety.validate(folder);
        Path excluded = undoLog.toAbsolutePath().normalize();
        Path realExcluded = Files.exists(excluded) ? excluded.toRealPath() : excluded;
        var files = new ArrayList<Path>();
        var skipped = new ArrayList<String>();
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                try {
                    String reason = exclusionReason(entry, excluded, realExcluded);
                    if (reason == null) {
                        files.add(entry);
                    } else {
                        skipped.add(entry.getFileName() + ": " + reason);
                    }
                } catch (IOException | SecurityException | OverlappingFileLockException exception) {
                    LOG.log(Level.INFO, "Cannot inspect file " + entry, exception);
                    skipped.add(entry.getFileName() + ": unavailable or in use");
                }
            }
        }
        var occupied = new HashSet<Path>();
        for (FileCategory category : FileCategory.values()) {
            Path destination = root.resolve(category.folderName());
            if (Files.notExists(destination, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)
                    || !destination.toRealPath().equals(destination)) {
                throw new IOException("The category folder " + category.folderName() + " is blocked or linked.");
            }
            try (var entries = Files.newDirectoryStream(destination)) {
                entries.forEach(occupied::add);
            }
        }
        files.sort(Comparator.comparing(Path::toString));
        skipped.sort(String::compareTo);
        return new ScanResult(root, files, occupied, skipped);
    }

    static String exclusionReason(Path file, Path excluded, Path realExcluded) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().equals(file)) {
            return "not a direct regular file";
        }
        if (file.equals(excluded) || file.toRealPath().equals(realExcluded)
                || (Files.exists(excluded) && Files.isSameFile(file, excluded))) {
            return "app undo log";
        }
        String name = file.getFileName().toString();
        if (name.startsWith(".") || Files.isHidden(file)) {
            return "hidden file";
        }
        var dos = Files.getFileAttributeView(file, java.nio.file.attribute.DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (dos != null) {
            DosFileAttributes attributes = dos.readAttributes();
            if (attributes.isSystem() || attributes.isHidden() || attributes.isReadOnly()) {
                return "protected file";
            }
        }
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && TEMP_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT))) {
            return "temporary download";
        }
        // Best-effort detection only: other programs may open the file after this check.
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            return lock == null ? "file in use" : null;
        }
    }
}
