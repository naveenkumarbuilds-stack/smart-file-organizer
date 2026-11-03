package com.smartfileorganizer.model;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelsTest {
    private final Path source = Path.of("photo.jpg");
    private final Path destination = Path.of("Images", "photo.jpg");

    @Test
    void preservesMoveData() {
        var plan = new PlannedMove(source, destination, FileCategory.IMAGES);
        var time = Instant.parse("2026-11-04T00:00:00Z");
        var record = new MoveRecord(source, destination, time);
        assertEquals(source, plan.source());
        assertEquals(destination, plan.destination());
        assertEquals(FileCategory.IMAGES, plan.category());
        assertEquals(source, record.originalPath());
        assertEquals(destination, record.newPath());
        assertEquals(time, record.timestamp());
    }

    @Test
    void rejectsMissingMoveData() {
        assertThrows(NullPointerException.class, () -> new PlannedMove(null, destination, FileCategory.IMAGES));
        assertThrows(NullPointerException.class, () -> new PlannedMove(source, null, FileCategory.IMAGES));
        assertThrows(NullPointerException.class, () -> new PlannedMove(source, destination, null));
        assertThrows(NullPointerException.class, () -> new MoveRecord(null, destination, Instant.EPOCH));
        assertThrows(NullPointerException.class, () -> new MoveRecord(source, null, Instant.EPOCH));
        assertThrows(NullPointerException.class, () -> new MoveRecord(source, destination, null));
    }

    @Test
    void preservesCountsAndRejectsNegativeCounts() {
        var result = new OrganizeResult(3, 2, 1);
        assertEquals(3, result.moved());
        assertEquals(2, result.skipped());
        assertEquals(1, result.failed());
        assertEquals(new OrganizeResult(0, 0, 0), new OrganizeResult(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new OrganizeResult(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new OrganizeResult(0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new OrganizeResult(0, 0, -1));
    }
}
