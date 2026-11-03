package com.smartfileorganizer.core;

import com.smartfileorganizer.model.*;
import com.smartfileorganizer.storage.UndoLogStore;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryRegressionTest {
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

    @Test void interruptedBatchRetainsCompletedMoveForRestartUndo() throws IOException {
        Files.writeString(folder.resolve("a.txt"), "first");
        Files.writeString(folder.resolve("b.txt"), "second");
        var plan = preview();
        assertThrows(IllegalStateException.class, () -> new FileMover(store).organize(folder, plan, percent -> {
            throw new IllegalStateException("Simulated interruption between rows");
        }));
        assertEquals("first", Files.readString(plan.get(0).destination()));
        assertEquals("second", Files.readString(plan.get(1).source()));
        assertEquals(1, store.read().entries().size());
        assertEquals(1, new UndoManager(new UndoLogStore(store.path())).undo().moved());
        assertEquals("first", Files.readString(folder.resolve("a.txt")));
    }

    @Test void changedContentWithOriginalSizeAndTimestampIsExcludedFromUndo() throws IOException {
        Files.writeString(folder.resolve("a.txt"), "first");
        var plan = preview();
        new FileMover(store).organize(folder, plan);
        Path moved = plan.getFirst().destination();
        var modified = Files.getLastModifiedTime(moved);
        Files.writeString(moved, "other");
        Files.setLastModifiedTime(moved, modified);
        assertEquals(new OrganizeResult(0, 1, 0), new UndoManager(store).undo());
        assertEquals("other", Files.readString(moved));
        assertFalse(Files.exists(folder.resolve("a.txt")));
        assertEquals(1, store.read().entries().size());
    }

    @Test void missingSourceDoesNotStopOtherPreviewRows() throws IOException {
        Files.writeString(folder.resolve("a.txt"), "first");
        Files.writeString(folder.resolve("b.txt"), "second");
        var plan = preview();
        Files.delete(plan.getFirst().source());
        assertEquals(new OrganizeResult(1, 1, 0), new FileMover(store).organize(folder, plan));
        assertEquals("second", Files.readString(plan.get(1).destination()));
    }

    @Test void categoryReplacedByFileDoesNotStopAnotherCategory() throws IOException {
        Files.writeString(folder.resolve("a.txt"), "document");
        Files.writeString(folder.resolve("b.jpg"), "image");
        var plan = preview();
        Files.writeString(folder.resolve("Documents"), "blocker");
        assertEquals(new OrganizeResult(1, 0, 1), new FileMover(store).organize(folder, plan));
        assertEquals("document", Files.readString(folder.resolve("a.txt")));
        assertEquals("blocker", Files.readString(folder.resolve("Documents")));
        assertEquals("image", Files.readString(folder.resolve("Images/b.jpg")));
    }

    @Test void journalLockRejectsConcurrentOperationBeforeMovingFiles() throws IOException {
        Files.writeString(folder.resolve("a.txt"), "keep");
        var plan = preview();
        store.exclusive(() -> {
            assertThrows(IOException.class, () -> new FileMover(new UndoLogStore(store.path())).organize(folder, plan));
            return null;
        });
        assertEquals("keep", Files.readString(folder.resolve("a.txt")));
        assertFalse(Files.exists(folder.resolve("Documents")));
    }

    @Test void journalFailureBeforeMoveLeavesSourceUntouched() throws IOException {
        Path source = Files.writeString(folder.resolve("a.txt"), "keep");
        var plan = preview();
        Files.createDirectories(store.path().getParent());
        Files.createDirectory(store.path());
        assertThrows(IOException.class, () -> new FileMover(store).organize(folder, plan));
        assertEquals("keep", Files.readString(source));
        assertFalse(Files.exists(plan.getFirst().destination()));
    }
}
