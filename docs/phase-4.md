# Phase 4 — Complete source

C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\core\FileMover.java

```java
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
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Executes selected preview rows and journals each move before it starts. */
public final class FileMover {
    private final UndoLogStore store;

    /** Uses the same journal as the undo manager. */
    public FileMover(UndoLogStore store) { this.store = store; }

    /** Executes only the supplied preview destinations under an exclusive journal lock. */
    public OrganizeResult organize(Path folder, List<PlannedMove> moves) throws IOException {
        var selected = List.copyOf(moves);
        return store.exclusive(() -> execute(new SafetyChecker().validate(folder), selected));
    }

    private OrganizeResult execute(Path root, List<PlannedMove> moves) throws IOException {
        Batch prior = store.read();
        List<Entry> entries = new ArrayList<>();
        int skipped = 0, failed = 0;
        for (var move : moves) {
            Path source = move.source(), target = move.destination();
            Entry intent;
            try {
                validatePaths(root, source, target);
                if (new CategoryClassifier().classify(source) != move.category()
                        || !source.getParent().equals(root)
                        || !target.getParent().equals(root.resolve(move.category().folderName()))
                        || unavailable(source, store.path()) || Files.exists(target, NOFOLLOW_LINKS)) {
                    skipped++;
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
                failed++;
                continue;
            }
            entries.add(intent);
            store.save(new Batch(root.toString(), entries));
            try {
                checkDirectory(target.getParent());
                Files.move(source, target); // No replacement and no cross-volume fallback.
            } catch (FileAlreadyExistsException e) {
                skipped++;
                entries.removeLast();
                store.save(entries.isEmpty() ? prior : new Batch(root.toString(), entries));
                continue;
            } catch (IOException | SecurityException e) {
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

```

C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\core\UndoManager.java

```java
package com.smartfileorganizer.core;

import com.smartfileorganizer.model.OrganizeResult;
import com.smartfileorganizer.storage.UndoLogStore;
import com.smartfileorganizer.storage.UndoLogStore.Batch;
import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.ArrayList;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Restores unchanged files from the persisted batch in reverse order. */
public final class UndoManager {
    private final UndoLogStore store;

    /** Opens the same journal used by the organizer. */
    public UndoManager(UndoLogStore store) { this.store = store; }

    /** Reports whether there are outstanding journal entries. */
    public boolean hasUndo() throws IOException {
        return !store.read().entries().isEmpty();
    }

    /** Restores eligible files and keeps unresolved entries available for retry. */
    public OrganizeResult undo() throws IOException {
        return store.exclusive(this::restore);
    }

    private OrganizeResult restore() throws IOException {
        Batch batch = store.read();
        var entries = new ArrayList<>(batch.entries());
        if (entries.isEmpty()) return new OrganizeResult(0, 0, 0);
        Path root = new SafetyChecker().validate(Path.of(batch.folder()));
        int restored = 0, skipped = 0, failed = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
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
                    skipped++;
                    continue;
                } else {
                    FileMover.checkDirectory(moved.getParent());
                    if (FileMover.unavailable(moved, store.path())
                            || !entry.signature().equals(FileMover.signature(moved))
                            || !Files.getFileStore(moved).equals(Files.getFileStore(root))) {
                        skipped++;
                        continue;
                    }
                    Files.move(moved, original);
                    restored++;
                }
            } catch (FileAlreadyExistsException e) {
                skipped++;
                continue;
            } catch (IOException | SecurityException | InvalidPathException | OverlappingFileLockException e) {
                failed++;
                continue;
            }
            entries.remove(i);
            store.save(new Batch(batch.folder(), entries));
        }
        return new OrganizeResult(restored, skipped, failed);
    }
}

```

C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\storage\UndoLogStore.java

```java
package com.smartfileorganizer.storage;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import static java.nio.file.StandardOpenOption.*;

/** Saves a complete journal atomically while serializing organizer instances. */
public final class UndoLogStore {
    private final Path path;
    private final Gson json = new Gson();

    /** Selects a journal file, allowing isolated storage during tests. */
    public UndoLogStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    /** Returns the user's standard journal location. */
    public static UndoLogStore forUser() {
        String base = System.getenv("LOCALAPPDATA");
        Path directory = base == null || base.isBlank()
                ? Path.of(System.getProperty("user.home"), ".local", "share") : Path.of(base);
        return new UndoLogStore(directory.resolve("SmartFileOrganizer/undo.json"));
    }

    /** Returns the path to exclude during scanning. */
    public Path path() { return path; }

    /** One durable intention or completed move, with the file's identity and content signature. */
    public record Entry(String original, String destination, String timestamp, String signature, boolean completed) {}

    /** Last batch and its outstanding entries. */
    public record Batch(String folder, List<Entry> entries) {
        /** Prevents accidental modification outside a journal update. */
        public Batch { entries = List.copyOf(entries); }
    }

    /** A journal operation performed under an exclusive process lock. */
    public interface Operation<T> {
        /** Runs the operation with exclusive access. */
        T run() throws IOException;
    }

    /** Executes a whole organize or undo operation under one lock. */
    public <T> T exclusive(Operation<T> operation) throws IOException {
        Files.createDirectories(path.getParent());
        try (var channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"), CREATE, WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another organizer is working.");
            return operation.run();
        } catch (OverlappingFileLockException e) {
            throw new IOException("Another organizer is working.", e);
        }
    }

    /** Loads the last batch or an empty journal on first use. */
    public Batch read() throws IOException {
        if (Files.notExists(path)) return new Batch("", List.of());
        try {
            Batch batch = json.fromJson(Files.readString(path), Batch.class);
            if (batch == null || batch.folder() == null) throw new IOException("Invalid undo history.");
            return batch;
        } catch (RuntimeException e) {
            throw new IOException("Cannot read undo history.", e);
        }
    }

    /** Forces a new snapshot to disk before atomically replacing the prior journal. */
    public void save(Batch batch) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), "undo-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(json.toJson(batch));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

```

C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\core\FolderScanner.java

```java
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

/** Collects eligible direct-child files and destination names for a preview. */
public final class FolderScanner {
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

```

C:\Users\navee\OneDrive\Desktop\opensorceproject\src\test\java\com\smartfileorganizer\core\MoveUndoTest.java

```java
package com.smartfileorganizer.core;

import com.smartfileorganizer.model.*;
import com.smartfileorganizer.storage.UndoLogStore;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MoveUndoTest {
    @TempDir Path temp;
    Path folder;
    UndoLogStore store;

    @BeforeEach void setup() throws IOException {
        folder = Files.createDirectory(temp.resolve("Downloads")).toRealPath();
        store = new UndoLogStore(temp.resolve("state/undo.json"));
    }

    List<PlannedMove> preview() throws IOException {
        var scan = new FolderScanner().scan(folder, store.path());
        return new MovePlanner().plan(scan.folder(), scan.files(), scan.occupied());
    }

    @Test void duplicatesAndRestart() throws IOException {
        Files.createDirectory(folder.resolve("Documents"));
        Files.writeString(folder.resolve("Documents/n.txt"), "old");
        Path file = Files.writeString(folder.resolve("n.txt"), "new");
        assertEquals(1, new FileMover(store).organize(folder, preview()).moved());
        assertEquals("new", Files.readString(folder.resolve("Documents/n (1).txt")));
        var manager = new UndoManager(new UndoLogStore(store.path()));
        assertTrue(manager.hasUndo());
        assertEquals(1, manager.undo().moved());
        assertEquals("new", Files.readString(file));
        assertEquals("old", Files.readString(folder.resolve("Documents/n.txt")));
        assertFalse(manager.hasUndo());
    }

    @Test void staleDestinationDoesNotChangePreviewOrStopOtherMoves() throws IOException {
        Files.writeString(folder.resolve("a.txt"), "a");
        Files.writeString(folder.resolve("b.txt"), "b");
        var plan = preview();
        Files.createDirectory(folder.resolve("Documents"));
        Files.writeString(plan.getFirst().destination(), "occupied");
        assertEquals(new OrganizeResult(1,1,0), new FileMover(store).organize(folder, plan));
        assertEquals("occupied", Files.readString(plan.getFirst().destination()));
        assertEquals("a", Files.readString(plan.getFirst().source()));
    }

    @Test void missingChangedAndOccupiedFilesRemainRecoverable() throws IOException {
        for (String name : List.of("a","b","c","d")) Files.writeString(folder.resolve(name+".txt"), name);
        var plan = preview();
        new FileMover(store).organize(folder, plan);
        Files.delete(plan.get(0).destination());
        Files.writeString(plan.get(1).destination(), "changed");
        Files.writeString(plan.get(2).source(), "replacement");
        assertEquals(new OrganizeResult(1,3,0), new UndoManager(store).undo());
        assertEquals(3, store.read().entries().size());
        assertEquals("changed", Files.readString(plan.get(1).destination()));
        assertEquals("replacement", Files.readString(plan.get(2).source()));
        Files.delete(plan.get(2).source());
        assertEquals(new OrganizeResult(1,2,0), new UndoManager(store).undo());
    }

    @Test void pendingRecordRecoversOnEitherSideOfMove() throws IOException {
        Path source = Files.writeString(folder.resolve("note.txt"), "note");
        Path target = Files.createDirectory(folder.resolve("Documents")).resolve("note.txt");
        var pending = new UndoLogStore.Batch(folder.toString(), List.of(new UndoLogStore.Entry(
                source.toString(), target.toString(), "2026-01-01T00:00:00Z", FileMover.signature(source), false)));
        store.exclusive(() -> { store.save(pending); return null; });
        assertEquals(0, new UndoManager(store).undo().moved());
        assertTrue(Files.exists(source));
        store.exclusive(() -> { store.save(pending); return null; });
        Files.move(source, target);
        assertEquals(1, new UndoManager(new UndoLogStore(store.path())).undo().moved());
        assertEquals("note", Files.readString(source));
    }

    @Test void corruptJournalBlocksMoves() throws IOException {
        Path file = Files.writeString(folder.resolve("a.txt"), "a");
        Files.createDirectories(store.path().getParent());
        Files.writeString(store.path(), "{bad");
        assertThrows(IOException.class, () -> new FileMover(store).organize(folder, preview()));
        assertEquals("a", Files.readString(file));
    }
}

```

