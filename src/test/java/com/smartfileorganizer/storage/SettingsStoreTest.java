package com.smartfileorganizer.storage;

import com.smartfileorganizer.core.OrganizerService;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SettingsStoreTest {
    @TempDir Path temp;

    @Test void firstUseAndUnicodeFolderSurviveRestart() throws IOException {
        Path path = temp.resolve("state/settings.json");
        var settings = new SettingsStore(path);
        assertTrue(settings.loadLastFolder().isEmpty());
        Path folder = temp.resolve("My files — 文件");
        settings.saveLastFolder(folder);
        assertEquals(folder.toAbsolutePath(), new SettingsStore(path).loadLastFolder().orElseThrow());
        settings.saveLastFolder(temp.resolve("Another folder"));
        assertEquals(temp.resolve("Another folder").toAbsolutePath(), settings.loadLastFolder().orElseThrow());
        try (var files = Files.list(path.getParent())) {
            assertEquals(List.of(path), files.toList());
        }
    }

    @Test void rejectsMalformedUnsupportedAndInvalidSettings() throws IOException {
        Path path = temp.resolve("settings.json");
        var store = new SettingsStore(path);
        for (String json : List.of("", "null", "[]", "{bad", "{}",
                "{\"version\":2,\"lastFolder\":\"relative\"}",
                "{\"version\":1,\"lastFolder\":false}",
                "{\"version\":1,\"lastFolder\":\"\"}",
                "{\"version\":1,\"lastFolder\":\"relative\"}")) {
            Files.writeString(path, json);
            assertThrows(IOException.class, store::loadLastFolder, json);
            assertEquals(json, Files.readString(path));
        }
    }

    @Test void missingOrUnsafeSavedFolderFallsBackAndPreservesUndo() throws IOException {
        var journal = new UndoLogStore(temp.resolve("state/undo.json"));
        var settings = new SettingsStore(journal.path().resolveSibling("settings.json"));
        var service = new OrganizerService(journal);
        settings.saveLastFolder(temp.resolve("missing"));
        assertTrue(service.startup().folder().isEmpty());
        assertFalse(service.startup().notice().isEmpty());
        settings.saveLastFolder(temp.toAbsolutePath().getRoot());
        assertTrue(service.startup().folder().isEmpty());
        assertFalse(Files.exists(journal.path()));
    }

    @Test void validPreviewIsRememberedButRejectedFolderDoesNotReplaceIt() throws IOException {
        Path folder = Files.createDirectory(temp.resolve("Downloads"));
        var journal = new UndoLogStore(temp.resolve("state/undo.json"));
        var service = new OrganizerService(journal);
        service.preview(folder);
        assertEquals(folder.toRealPath(), new OrganizerService(journal).startup().folder().orElseThrow());
        assertThrows(IOException.class, () -> service.preview(folder.getRoot()));
        assertEquals(folder.toRealPath(), service.startup().folder().orElseThrow());
    }

    @Test void failedSettingsSaveDoesNotPreventPreview() throws IOException {
        Path folder = Files.createDirectory(temp.resolve("Downloads"));
        Files.writeString(folder.resolve("a.txt"), "data");
        var journal = new UndoLogStore(temp.resolve("state/undo.json"));
        Files.createDirectories(journal.path().resolveSibling("settings.json"));
        var preview = new OrganizerService(journal).preview(folder);
        assertEquals(1, preview.moves().size());
        assertFalse(preview.notice().isEmpty());
        assertEquals("data", Files.readString(folder.resolve("a.txt")));
    }
}
