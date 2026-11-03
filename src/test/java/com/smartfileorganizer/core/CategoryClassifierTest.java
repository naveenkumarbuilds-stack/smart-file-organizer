package com.smartfileorganizer.core;

import com.smartfileorganizer.model.FileCategory;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class CategoryClassifierTest {
    private final CategoryClassifier classifier = new CategoryClassifier();

    static Stream<Arguments> extensions() {
        return Arrays.stream(FileCategory.values()).flatMap(category ->
                category.extensions().stream().map(extension -> Arguments.of(category, extension)));
    }

    @ParameterizedTest
    @MethodSource("extensions")
    void classifiesEveryMappedExtensionInBothCases(FileCategory category, String extension) {
        assertEquals(category, classifier.classify(Path.of("sample." + extension)));
        assertEquals(category, classifier.classify(Path.of("sample." + extension.toUpperCase(Locale.ROOT))));
    }

    @ParameterizedTest
    @CsvSource({"README, OTHERS", "unknown.xyz, OTHERS", "file., OTHERS",
            ".gitignore, OTHERS", ".hidden.jpg, OTHERS", "backup.tar.gz, ARCHIVES",
            "photo.final.JpG, IMAGES", "report.pdf.exe, INSTALLERS",
            "folder.jpg/notes.txt, DOCUMENTS"})
    void handlesFilenameEdgeCases(String name, FileCategory expected) {
        assertEquals(expected, classifier.classify(Path.of(name)));
    }

    @Test
    void handlesEmptyAndRootPaths() {
        assertEquals(FileCategory.OTHERS, classifier.classify(Path.of("")));
        assertEquals(FileCategory.OTHERS, classifier.classify(Path.of("").toAbsolutePath().getRoot()));
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> classifier.classify(null));
    }

    @Test
    void categoryMappingsAreUniqueAndImmutable() {
        var seen = new HashSet<String>();
        for (FileCategory category : FileCategory.values()) {
            for (String extension : category.extensions()) {
                assertTrue(seen.add(extension), "Duplicate extension: " + extension);
            }
            assertThrows(UnsupportedOperationException.class, () -> category.extensions().add("new"));
        }
        assertEquals(49, seen.size());
    }
}
