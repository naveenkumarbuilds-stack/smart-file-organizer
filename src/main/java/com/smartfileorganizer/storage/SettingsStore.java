package com.smartfileorganizer.storage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;
import static java.nio.file.StandardOpenOption.WRITE;

/** Persists the last successfully previewed folder separately from undo history. */
public final class SettingsStore {
    private final Path path;
    private final Gson gson = new Gson();

    /** Uses the supplied settings path, including isolated paths for tests. */
    public SettingsStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    /** Loads the saved folder, or an empty value on first use. */
    public Optional<Path> loadLastFolder() throws IOException {
        if (Files.notExists(path)) return Optional.empty();
        try {
            var document = JsonParser.parseString(Files.readString(path));
            if (!document.isJsonObject()) throw new IllegalArgumentException("Expected settings object");
            var object = document.getAsJsonObject();
            var version = object.get("version");
            if (version == null || !version.isJsonPrimitive()
                    || !version.getAsJsonPrimitive().isNumber() || !"1".equals(version.getAsString())) {
                throw new IllegalArgumentException("Unsupported settings version");
            }
            var value = object.get("lastFolder");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().isBlank()) {
                throw new IllegalArgumentException("Invalid folder setting");
            }
            Path folder = Path.of(value.getAsString());
            if (!folder.isAbsolute()) throw new IllegalArgumentException("Expected absolute folder");
            return Optional.of(folder.normalize());
        } catch (RuntimeException exception) {
            throw new IOException("Saved settings could not be read. Please choose your folder again.", exception);
        }
    }

    /** Atomically saves a folder after a successful preview, preserving old settings on failure. */
    public void saveLastFolder(Path folder) throws IOException {
        var document = new JsonObject();
        document.addProperty("version", 1);
        document.addProperty("lastFolder", folder.toAbsolutePath().normalize().toString());
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "settings-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(gson.toJson(document));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
