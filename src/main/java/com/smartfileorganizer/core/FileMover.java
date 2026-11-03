package com.smartfileorganizer.core;

import com.smartfileorganizer.model.*;
import com.smartfileorganizer.storage.UndoLogStore;
import com.smartfileorganizer.storage.UndoLogStore.*;
import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Executes selected preview rows and journals each move before it starts. */
public final class FileMover {
    private static final Logger LOG = Logger.getLogger(FileMover.class.getName());
    private final UndoLogStore store;

    /** Uses the same journal as the undo manager. */
    public FileMover(UndoLogStore store) { this.store = store; }

    /** Executes only the supplied preview destinations under an exclusive journal lock. */
    public OrganizeResult organize(Path folder, List<PlannedMove> moves) throws IOException {
        return organize(folder, moves, percent -> {});
    }

    /** Executes approved moves and reports the percentage of rows processed. */
    public OrganizeResult organize(Path folder, List<PlannedMove> moves, IntConsumer progress) throws IOException {
        var selected = List.copyOf(moves);
        Objects.requireNonNull(progress, "progress");
        return store.exclusive(() -> execute(new SafetyChecker().validate(folder), selected, progress));
    }

    private OrganizeResult execute(Path root, List<PlannedMove> moves, IntConsumer progress) throws IOException {
        Batch prior = store.read();
        List<Entry> entries = new ArrayList<>();
        int skipped = 0, failed = 0;
        int processed = 0;
        for (var move : moves) {
            try {
                Path source = move.source(), target = move.destination();
                Entry intent;
                try {
                    validatePaths(root, source, target);
                    if (new CategoryClassifier().classify(source) != move.category()
                            || !source.getParent().equals(root)
                            || !target.getParent().equals(root.resolve(move.category().folderName()))
                            || unavailable(source, store.path()) || Files.exists(target, NOFOLLOW_LINKS)) {
                        skipped++;
                        LOG.info("Skipped unavailable source or occupied destination: " + source);
                        continue;
                    }
                    Path directory = target.getParent();
                    if (Files.notExists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory);
                    checkDirectory(directory);
                    if (!Files.getFileStore(source).equals(Files.getFileStore(directory))) {
                        skipped++;
                        continue;
                    }
                    intent = new Entry(source.toString(), target.toString(), Instant.now().toString(),
                            signature(source), false);
                } catch (IOException | SecurityException | OverlappingFileLockException e) {
                    LOG.log(Level.WARNING, "Cannot prepare move for " + source, e);
                    failed++;
                    continue;
                }
                entries.add(intent);
                store.save(new Batch(root.toString(), entries));
                try {
                    checkDirectory(target.getParent());
                    Files.move(source, target); // No replacement and no cross-volume fallback.
                } catch (FileAlreadyExistsException e) {
                    LOG.info("Destination became occupied: " + target);
                    skipped++;
                    entries.removeLast();
                    store.save(entries.isEmpty() ? prior : new Batch(root.toString(), entries));
                    continue;
                } catch (IOException | SecurityException e) {
                    LOG.log(Level.WARNING, "Cannot move " + source + " to " + target, e);
                    // Preserve an ambiguous outcome for recovery if the source disappeared.
                    if (!Files.exists(source, NOFOLLOW_LINKS)) throw new IOException("Check undo history before continuing.", e);
                    failed++;
                    entries.removeLast();
                    store.save(entries.isEmpty() ? prior : new Batch(root.toString(), entries));
                    continue;
                }
                entries.set(entries.size()-1, new Entry(intent.original(), intent.destination(),
                        intent.timestamp(), intent.signature(), true));
                store.save(new Batch(root.toString(), entries));
            } finally {
                progress.accept((int) (++processed * 100L / moves.size()));
            }
        }
        return new OrganizeResult(entries.size(), skipped, failed);
    }

    static void validatePaths(Path root, Path source, Path target) throws IOException {
        if (!root.equals(source.getParent()) || !source.equals(source.normalize())
                || !target.equals(target.normalize()) || target.getParent() == null
                || !root.resolve(new CategoryClassifier().classify(source).folderName()).equals(target.getParent()))
            throw new IOException("Move paths do not match the selected folder.");
    }

    static void checkDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || !directory.toRealPath().equals(directory))
            throw new IOException("Category folder changed or is linked.");
    }

    static boolean unavailable(Path file, Path journal) throws IOException {
        return FolderScanner.exclusionReason(file, journal,
                Files.exists(journal) ? journal.toRealPath() : journal) != null;
    }

    static String signature(Path file) throws IOException {
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Expected a regular file.");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        try (var input = Files.newInputStream(file, NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return attributes.fileKey() + "/" + attributes.creationTime() + "/" +
                attributes.lastModifiedTime() + "/" + HexFormat.of().formatHex(digest.digest());
    }
}
