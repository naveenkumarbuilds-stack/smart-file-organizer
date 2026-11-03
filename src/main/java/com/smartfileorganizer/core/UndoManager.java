package com.smartfileorganizer.core;

import com.smartfileorganizer.model.OrganizeResult;
import com.smartfileorganizer.storage.UndoLogStore;
import com.smartfileorganizer.storage.UndoLogStore.Batch;
import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Restores unchanged files from the persisted batch in reverse order. */
public final class UndoManager {
    private static final Logger LOG = Logger.getLogger(UndoManager.class.getName());
    private final UndoLogStore store;

    /** Opens the same journal used by the organizer. */
    public UndoManager(UndoLogStore store) { this.store = store; }

    /** Reports whether there are outstanding journal entries. */
    public boolean hasUndo() throws IOException {
        return !store.read().entries().isEmpty();
    }

    /** Restores eligible files and keeps unresolved entries available for retry. */
    public OrganizeResult undo() throws IOException {
        return undo(percent -> {});
    }

    /** Restores eligible files and reports the percentage of entries processed. */
    public OrganizeResult undo(IntConsumer progress) throws IOException {
        Objects.requireNonNull(progress, "progress");
        return store.exclusive(() -> restore(progress));
    }

    private OrganizeResult restore(IntConsumer progress) throws IOException {
        Batch batch = store.read();
        var entries = new ArrayList<>(batch.entries());
        if (entries.isEmpty()) return new OrganizeResult(0, 0, 0);
        Path root = new SafetyChecker().validate(Path.of(batch.folder()));
        int restored = 0, skipped = 0, failed = 0;
        int total = entries.size();
        for (int i = entries.size() - 1; i >= 0; i--) {
            try {
                var entry = entries.get(i);
                try {
                    Path original = Path.of(entry.original()), moved = Path.of(entry.destination());
                    FileMover.validatePaths(root, original, moved);
                    if (original.equals(store.path()) || moved.equals(store.path())) {
                        throw new IOException("Invalid journal path.");
                    }
                    if (Files.notExists(moved, NOFOLLOW_LINKS) && Files.isRegularFile(original, NOFOLLOW_LINKS)
                            && entry.signature().equals(FileMover.signature(original))) {
                        // The move never ran, or a previous undo completed before its journal update.
                        skipped++;
                    } else if (!Files.notExists(original, NOFOLLOW_LINKS) || Files.notExists(moved, NOFOLLOW_LINKS)) {
                        LOG.info("Undo skipped missing destination or occupied original: " + original);
                        skipped++;
                        continue;
                    } else {
                        FileMover.checkDirectory(moved.getParent());
                        if (FileMover.unavailable(moved, store.path())
                                || !entry.signature().equals(FileMover.signature(moved))
                                || !Files.getFileStore(moved).equals(Files.getFileStore(root))) {
                            LOG.info("Undo skipped changed, unavailable, or relocated file: " + moved);
                            skipped++;
                            continue;
                        }
                        Files.move(moved, original);
                        restored++;
                    }
                } catch (FileAlreadyExistsException e) {
                    LOG.log(Level.INFO, "Original location became occupied during undo", e);
                    skipped++;
                    continue;
                } catch (IOException | SecurityException | InvalidPathException | OverlappingFileLockException e) {
                    LOG.log(Level.WARNING, "Cannot undo entry " + entry.original(), e);
                    failed++;
                    continue;
                }
                entries.remove(i);
                store.save(new Batch(batch.folder(), entries));
            } finally {
                progress.accept((int) ((total - i) * 100L / total));
            }
        }
        return new OrganizeResult(restored, skipped, failed);
    }
}
