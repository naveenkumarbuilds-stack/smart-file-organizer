package com.smartfileorganizer.model;

import java.util.Set;

/** Default destination folders and their supported extensions. */
public enum FileCategory {
    IMAGES("Images", Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "heic")),
    DOCUMENTS("Documents", Set.of("pdf", "doc", "docx", "txt", "rtf", "odt", "ppt", "pptx", "xls", "xlsx", "csv")),
    VIDEOS("Videos", Set.of("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm")),
    AUDIO("Audio", Set.of("mp3", "wav", "flac", "aac", "ogg", "m4a")),
    ARCHIVES("Archives", Set.of("zip", "rar", "7z", "tar", "gz")),
    INSTALLERS("Installers", Set.of("exe", "msi", "dmg", "deb", "apk")),
    CODE("Code", Set.of("java", "py", "js", "html", "css", "json", "xml")),
    OTHERS("Others", Set.of());

    private final String folderName;
    private final Set<String> extensions;

    FileCategory(String folderName, Set<String> extensions) {
        this.folderName = folderName;
        this.extensions = extensions;
    }

    /** Returns the destination folder name. */
    public String folderName() {
        return folderName;
    }

    /** Returns the immutable set of lowercase extensions without dots. */
    public Set<String> extensions() {
        return extensions;
    }
}
