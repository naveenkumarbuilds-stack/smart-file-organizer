package com.smartfileorganizer.core;

import com.smartfileorganizer.model.PlannedMove;
import com.smartfileorganizer.model.OrganizeResult;
import com.smartfileorganizer.storage.UndoLogStore;
import com.smartfileorganizer.storage.SettingsStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.IntConsumer;

/** Small application service used by the desktop interface. All calls may access disk. */
public final class OrganizerService {
    private final UndoLogStore store;
    private final SettingsStore settings;
    private static final Logger LOG = Logger.getLogger(OrganizerService.class.getName());

    /** Uses the supplied journal, allowing isolated application tests. */
    public OrganizerService(UndoLogStore store) {
        this.store = store;
        this.settings = new SettingsStore(store.path().resolveSibling("settings.json"));
    }

    /** Immutable preview containing the canonical folder and scan exclusions. */
    public record Preview(Path folder, List<PlannedMove> moves, List<String> skipped, String notice) {
        /** Creates a preview without a settings warning. */
        public Preview(Path folder, List<PlannedMove> moves, List<String> skipped) {
            this(folder, moves, skipped, "");
        }
        /** Copies the collections to keep the preview stable. */
        public Preview {
            moves = List.copyOf(moves);
            skipped = List.copyOf(skipped);
        }
    }

    /** Scans a folder and plans its eligible moves. */
    public Preview preview(Path folder) throws IOException {
        var scan = new FolderScanner().scan(folder, store.path());
        var moves = new MovePlanner().plan(scan.folder(), scan.files(), scan.occupied());
        String notice = "";
        try {
            settings.saveLastFolder(scan.folder());
        } catch (IOException | SecurityException exception) {
            LOG.log(Level.WARNING, "Cannot save last folder", exception);
            notice = "The preview is ready, but this folder could not be remembered. Check app-data permissions and disk space.";
        }
        return new Preview(scan.folder(), moves, scan.skipped(), notice);
    }

    /** Startup settings together with any recoverable loading warning. */
    public record Startup(Optional<Path> folder, String notice) {}

    /** Loads and validates the saved folder, falling back to folder selection on failure. */
    public Startup startup() {
        try {
            Optional<Path> saved = settings.loadLastFolder();
            if (saved.isEmpty()) return new Startup(Optional.empty(), "");
            return new Startup(Optional.of(new SafetyChecker().validate(saved.get())), "");
        } catch (IOException | SecurityException exception) {
            LOG.log(Level.WARNING, "Saved folder is unavailable", exception);
            return new Startup(Optional.empty(),
                    "The saved folder or settings are unavailable. Please choose a folder again.");
        }
    }

    /** Reports whether persisted undo entries remain. */
    public boolean hasUndo() throws IOException {
        return new UndoManager(store).hasUndo();
    }

    /** Executes the selected preview rows and reports progress. */
    public OrganizeResult organize(Path folder, List<PlannedMove> moves, IntConsumer progress) throws IOException {
        return new FileMover(store).organize(folder, moves, progress);
    }

    /** Undoes the saved batch and reports progress. */
    public OrganizeResult undo(IntConsumer progress) throws IOException {
        return new UndoManager(store).undo(progress);
    }
}
