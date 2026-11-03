package com.smartfileorganizer.storage;

import com.google.gson.Gson;
import com.smartfileorganizer.model.FileCategory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
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
            if (!batch.entries().isEmpty() && !Path.of(batch.folder()).isAbsolute()) {
                throw new IOException("Saved undo history is invalid. Keep the history file for recovery.");
            }
            Path root = Path.of(batch.folder());
            Set<String> sources = new HashSet<>();
            Set<String> destinations = new HashSet<>();
            for (Entry entry : batch.entries()) {
                if (entry == null || entry.original() == null || entry.destination() == null
                        || entry.timestamp() == null || entry.signature() == null || entry.signature().isBlank()
                        || !Path.of(entry.original()).isAbsolute() || !Path.of(entry.destination()).isAbsolute()) {
                    throw new IOException("Saved undo history is invalid. Keep the history file for recovery.");
                }
                Path original = Path.of(entry.original());
                Path destination = Path.of(entry.destination());
                boolean categoryFolder = false;
                for (FileCategory category : FileCategory.values()) {
                    if (root.resolve(category.folderName()).equals(destination.getParent())) {
                        categoryFolder = true;
                        break;
                    }
                }
                if (!root.equals(root.normalize()) || !root.equals(original.getParent())
                        || !original.equals(original.normalize()) || !destination.equals(destination.normalize())
                        || !categoryFolder
                        || !sources.add(original.toString().toLowerCase(Locale.ROOT))
                        || !destinations.add(destination.toString().toLowerCase(Locale.ROOT))) {
                    throw new IOException("Saved undo history has conflicting paths. Keep the history file for recovery.");
                }
                java.time.Instant.parse(entry.timestamp());
            }
            return batch;
        } catch (RuntimeException e) {
            throw new IOException("Saved undo history could not be read. Keep the history file for recovery.", e);
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
