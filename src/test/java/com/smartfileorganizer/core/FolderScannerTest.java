package com.smartfileorganizer.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FolderScannerTest {
    @TempDir Path temp;
    private final FolderScanner scanner = new FolderScanner();

    private FolderScanner.ScanResult scan() throws IOException {
        return scanner.scan(temp, temp.resolve("undo.json"));
    }

    @Test
    void scansOnlyEligibleDirectFilesAndExcludesUndoAndDownloads() throws IOException {
        Path photo = Files.writeString(temp.resolve("photo.jpg"), "photo");
        for (String name : List.of("undo.json", ".hidden", "download.CRDOWNLOAD", "video.part", "work.tmp")) {
            Files.writeString(temp.resolve(name), "unchanged");
        }
        Path nested = Files.createDirectory(temp.resolve("nested"));
        Files.writeString(nested.resolve("keep.txt"), "nested");
        var result = scan();
        assertEquals(List.of(photo.toRealPath()), result.files());
        assertEquals(6, result.skipped().size());
        assertEquals("photo", Files.readString(photo));
        assertEquals("nested", Files.readString(nested.resolve("keep.txt")));
        assertFalse(Files.exists(temp.resolve("Images")));
        assertThrows(UnsupportedOperationException.class, () -> result.files().clear());
    }

    @Test
    void skipsLockedFileAndContinues() throws IOException {
        Path locked = Files.writeString(temp.resolve("locked.txt"), "locked");
        Path free = Files.writeString(temp.resolve("free.txt"), "free");
        try (var channel = FileChannel.open(locked, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            var result = scan();
            assertEquals(List.of(free.toRealPath()), result.files());
            assertTrue(result.skipped().stream().anyMatch(message -> message.startsWith("locked.txt:")));
        }
        assertEquals(2, scan().files().size());
    }

    @Test
    void skipsWindowsHiddenSystemAndReadOnlyFiles() throws IOException {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        for (String flag : List.of("hidden", "system", "readonly")) {
            Path file = Files.writeString(temp.resolve(flag + ".txt"), "protected");
            var view = Files.getFileAttributeView(file, DosFileAttributeView.class);
            try {
                switch (flag) {
                    case "hidden" -> view.setHidden(true);
                    case "system" -> view.setSystem(true);
                    case "readonly" -> view.setReadOnly(true);
                    default -> throw new AssertionError(flag);
                }
                assertFalse(scan().files().contains(file.toRealPath()));
            } finally {
                view.setHidden(false);
                view.setSystem(false);
                view.setReadOnly(false);
            }
        }
    }

    @Test
    void snapshotsOccupiedNamesAndPlansWithoutMoving() throws IOException {
        Path images = Files.createDirectory(temp.resolve("Images"));
        Path existing = Files.writeString(images.resolve("photo.jpg"), "existing");
        Path source = Files.writeString(temp.resolve("photo.jpg"), "new");
        var result = scan();
        var plan = new MovePlanner().plan(result.folder(), result.files(), result.occupied());
        assertTrue(result.occupied().contains(existing.toRealPath()));
        assertEquals(images.toRealPath().resolve("photo (1).jpg"), plan.getFirst().destination());
        assertEquals("existing", Files.readString(existing));
        assertEquals("new", Files.readString(source));
        assertFalse(Files.exists(plan.getFirst().destination()));
    }

    @Test
    void refusesBlockedCategoryAndDeletedSourceFolder() throws IOException {
        Files.writeString(temp.resolve("Images"), "cannot be a directory");
        assertThrows(IOException.class, this::scan);
        Path removed = Files.createDirectory(temp.resolve("removed"));
        Files.delete(removed);
        assertThrows(IOException.class, () -> scanner.scan(removed, temp.resolve("undo.json")));
    }
}
