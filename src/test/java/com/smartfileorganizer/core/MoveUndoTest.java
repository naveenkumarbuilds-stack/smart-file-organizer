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
