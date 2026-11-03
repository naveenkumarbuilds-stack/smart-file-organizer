package com.smartfileorganizer.core;

import com.smartfileorganizer.model.FileCategory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MovePlannerTest {
    @TempDir Path temp;
    private final MovePlanner planner = new MovePlanner();

    @Test
    void plansFromSnapshotsWithoutCreatingAnything() {
        Path root = temp.resolve("does-not-exist");
        Path source = root.resolve("photo.JPG");
        var plan = planner.plan(root, List.of(source), Set.of());
        assertEquals(source, plan.getFirst().source());
        assertEquals(root.resolve("Images/photo.JPG"), plan.getFirst().destination());
        assertEquals(FileCategory.IMAGES, plan.getFirst().category());
        assertFalse(Files.exists(root));
        assertThrows(UnsupportedOperationException.class, () -> plan.clear());
    }

    @Test
    void reservesExistingAndBatchNamesCaseInsensitively() {
        Path first = temp.resolve("photo (1).jpg");
        Path second = temp.resolve("photo.jpg");
        Set<Path> occupied = Set.of(temp.resolve("Images/PHOTO.JPG"));
        var plan = planner.plan(temp, List.of(second, first), occupied);
        assertEquals(temp.resolve("Images/photo (1).jpg"), plan.get(0).destination());
        assertEquals(temp.resolve("Images/photo (2).jpg"), plan.get(1).destination());
        assertEquals(plan, planner.plan(temp, List.of(first, second), occupied));
        assertEquals(1, occupied.size());
    }

    @Test
    void renamesExtensionlessAndMultiDotFiles() {
        var plan = planner.plan(temp, List.of(temp.resolve("README"), temp.resolve("backup.tar.gz")),
                Set.of(temp.resolve("Others/README"), temp.resolve("Archives/backup.tar.gz")));
        assertEquals(temp.resolve("Others/README (1)"), plan.get(0).destination());
        assertEquals(temp.resolve("Archives/backup.tar (1).gz"), plan.get(1).destination());
    }

    @Test
    void rejectsNestedOutsideAndDuplicateSources() {
        Path file = temp.resolve("file.txt");
        for (List<Path> sources : List.of(List.of(temp.resolve("nested/file.txt")),
                List.of(temp.getParent().resolve("outside.txt")), List.of(file, file))) {
            assertThrows(IllegalArgumentException.class, () -> planner.plan(temp, sources, Set.of()));
        }
        assertTrue(planner.plan(temp, List.of(), Set.of()).isEmpty());
    }
}
