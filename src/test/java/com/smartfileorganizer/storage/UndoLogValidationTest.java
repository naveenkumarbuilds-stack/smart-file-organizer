package com.smartfileorganizer.storage;

import com.google.gson.Gson;
import com.smartfileorganizer.core.OrganizerService;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class UndoLogValidationTest {
    @TempDir Path temp;

    @Test void conflictingOrOutsidePathsCannotReplaceExistingHistory() throws IOException {
        Path folder = Files.createDirectory(temp.resolve("Downloads")).toRealPath();
        Path journal = temp.resolve("state/undo.json");
        Files.createDirectories(journal.getParent());
        var entry = new UndoLogStore.Entry(folder.resolve("a.txt").toString(),
                folder.resolve("Documents/a.txt").toString(), "2026-11-04T00:00:00Z", "fingerprint", true);
        var outside = new UndoLogStore.Entry(temp.resolve("outside.txt").toString(),
                entry.destination(), entry.timestamp(), entry.signature(), true);
        var invalidCategory = new UndoLogStore.Entry(entry.original(), folder.resolve("arbitrary/a.txt").toString(),
                entry.timestamp(), entry.signature(), true);
        for (var entries : List.of(List.of(entry, entry), List.of(outside), List.of(invalidCategory))) {
            String damaged = new Gson().toJson(new UndoLogStore.Batch(folder.toString(), entries));
            Files.writeString(journal, damaged);
            assertThrows(IOException.class, () -> new UndoLogStore(journal).read());
            assertEquals(damaged, Files.readString(journal));
        }
    }

    @Test void invalidEntryBlocksOrganizationAndPreservesHistory() throws IOException {
        Path folder = Files.createDirectory(temp.resolve("Downloads"));
        Path file = Files.writeString(folder.resolve("a.txt"), "keep");
        Path journal = temp.resolve("state/undo.json");
        Files.createDirectories(journal.getParent());
        var badEntry = new UndoLogStore.Entry(file.toString(), folder.resolve("Documents/a.txt").toString(),
                null, "signature", true);
        String damaged = new Gson().toJson(new UndoLogStore.Batch(folder.toString(), List.of(badEntry)));
        Files.writeString(journal, damaged);
        var service = new OrganizerService(new UndoLogStore(journal));
        var preview = service.preview(folder);
        assertThrows(IOException.class, service::hasUndo);
        assertThrows(IOException.class, () -> service.organize(preview.folder(), preview.moves(), value -> {}));
        assertEquals("keep", Files.readString(file));
        assertEquals(damaged, Files.readString(journal));
    }
}
