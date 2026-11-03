# Phase 6 — Saved settings, logging, and error handling

## 1. Goal
Remember the last successfully previewed folder across restarts. Make settings failures recoverable, validate persisted undo entries, and provide actionable errors backed by detailed logs.

## 2. Files
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\storage\SettingsStore.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\ui\FriendlyErrors.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\Main.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\core\OrganizerService.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\ui\MainFrame.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\core\FileMover.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\core\UndoManager.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\core\FolderScanner.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\storage\UndoLogStore.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\test\java\com\smartfileorganizer\storage\SettingsStoreTest.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\test\java\com\smartfileorganizer\storage\UndoLogValidationTest.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\test\java\com\smartfileorganizer\ui\FriendlyErrorsTest.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\test\java\com\smartfileorganizer\ui\DesktopFlowTest.java

## 3. Complete code

### src/main/java/com/smartfileorganizer/storage/SettingsStore.java

```java
package com.smartfileorganizer.storage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;
import static java.nio.file.StandardOpenOption.WRITE;

/** Persists the last successfully previewed folder separately from undo history. */
public final class SettingsStore {
    private final Path path;
    private final Gson gson = new Gson();

    /** Uses the supplied settings path, including isolated paths for tests. */
    public SettingsStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    /** Loads the saved folder, or an empty value on first use. */
    public Optional<Path> loadLastFolder() throws IOException {
        if (Files.notExists(path)) return Optional.empty();
        try {
            var document = JsonParser.parseString(Files.readString(path));
            if (!document.isJsonObject()) throw new IllegalArgumentException("Expected settings object");
            var object = document.getAsJsonObject();
            var version = object.get("version");
            if (version == null || !version.isJsonPrimitive()
                    || !version.getAsJsonPrimitive().isNumber() || !"1".equals(version.getAsString())) {
                throw new IllegalArgumentException("Unsupported settings version");
            }
            var value = object.get("lastFolder");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().isBlank()) {
                throw new IllegalArgumentException("Invalid folder setting");
            }
            Path folder = Path.of(value.getAsString());
            if (!folder.isAbsolute()) throw new IllegalArgumentException("Expected absolute folder");
            return Optional.of(folder.normalize());
        } catch (RuntimeException exception) {
            throw new IOException("Saved settings could not be read. Please choose your folder again.", exception);
        }
    }

    /** Atomically saves a folder after a successful preview, preserving old settings on failure. */
    public void saveLastFolder(Path folder) throws IOException {
        var document = new JsonObject();
        document.addProperty("version", 1);
        document.addProperty("lastFolder", folder.toAbsolutePath().normalize().toString());
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "settings-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(gson.toJson(document));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
```

### src/main/java/com/smartfileorganizer/ui/FriendlyErrors.java

```java
package com.smartfileorganizer.ui;

import java.nio.file.*;
import java.util.Locale;

/** Converts operating-system errors into short, actionable messages. */
public final class FriendlyErrors {
    private FriendlyErrors() {
    }

    /** Returns a message suitable for an error dialog, with full details left in the log. */
    public static String message(Throwable error) {
        if (error instanceof AccessDeniedException || error instanceof SecurityException) {
            return "Access was denied. Check folder permissions and close applications using these files.";
        }
        if (error instanceof NoSuchFileException) {
            return "The file or folder is no longer available. Choose an existing folder and refresh the preview.";
        }
        if (error instanceof FileAlreadyExistsException) {
            return "A destination is now occupied. Refresh the preview to calculate a free name.";
        }
        if (error instanceof FileSystemException filesystem) {
            String reason = filesystem.getReason();
            String text = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
            if (text.contains("space") || text.contains("disk full")) {
                return "The disk may be full. Free some space, then refresh or try Undo.";
            }
            if (text.contains("too long") || text.contains("filename or extension is too long")) {
                return "A file path is too long. Try a folder closer to the drive root or shorten the filename.";
            }
            return "A file operation could not finish. Check the drive connection, permissions, and files in use.";
        }
        if (error instanceof java.io.IOException && error.getMessage() != null) {
            return error.getMessage();
        }
        return "The operation could not finish. Refresh the preview or try Undo. Details are in the application log.";
    }
}
```

### src/main/java/com/smartfileorganizer/Main.java

```java
package com.smartfileorganizer;

import com.formdev.flatlaf.FlatLightLaf;
import com.smartfileorganizer.core.OrganizerService;
import com.smartfileorganizer.storage.UndoLogStore;
import com.smartfileorganizer.ui.MainFrame;
import java.io.IOException;
import java.nio.file.Files;
import java.util.logging.*;
import javax.swing.SwingUtilities;
import javax.swing.JOptionPane;

/** Entry point for Smart File Organizer. */
public final class Main {
    private Main() {
    }

    /**
     * Starts logging and creates the desktop interface on Swing's event thread.
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        UndoLogStore store = UndoLogStore.forUser();
        String loggingWarning = "";
        try {
            Files.createDirectories(store.path().getParent());
            var handler = new FileHandler(store.path().resolveSibling("organizer-%g.log").toString(),
                    1_000_000, 3, true);
            handler.setFormatter(new SimpleFormatter());
            Logger.getLogger("com.smartfileorganizer").addHandler(handler);
        } catch (IOException | SecurityException exception) {
            Logger.getLogger(Main.class.getName()).log(Level.WARNING, "Cannot open application log", exception);
            loggingWarning = "The application log could not be opened. Check app-data permissions and disk space.";
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, "Unexpected application error", exception);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "An unexpected error occurred. Refresh the preview before continuing. Details are in the application log.",
                    "Application error", JOptionPane.ERROR_MESSAGE));
        });
        FlatLightLaf.setup();
        String notice = loggingWarning;
        SwingUtilities.invokeLater(() -> {
            var window = new MainFrame(new OrganizerService(store));
            window.setVisible(true);
            if (!notice.isEmpty()) JOptionPane.showMessageDialog(window, notice, "Logging unavailable",
                    JOptionPane.WARNING_MESSAGE);
        });
    }
}
```

### src/main/java/com/smartfileorganizer/core/OrganizerService.java

```java
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
```

### src/main/java/com/smartfileorganizer/ui/MainFrame.java

```java
package com.smartfileorganizer.ui;

import com.smartfileorganizer.core.OrganizerService;
import com.smartfileorganizer.model.OrganizeResult;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.logging.*;
import javax.swing.*;

/** Single-window organizer with all service work performed by background workers. */
public final class MainFrame extends JFrame {
    private static final Logger LOG = Logger.getLogger(MainFrame.class.getName());
    private final OrganizerService service;
    private final PreviewTableModel model = new PreviewTableModel();
    private final JTable table = new JTable(model);
    private final JTextField folder = new JTextField("Choose a folder to begin");
    private final JButton choose = new JButton("Choose folder");
    private final JButton refresh = new JButton("Refresh preview");
    private final JButton organize = new JButton("Organize");
    private final JButton undo = new JButton("Undo last organization");
    private final JButton details = new JButton("Skipped files");
    private final JLabel status = new JLabel("Checking saved undo history...");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private OrganizerService.Preview preview;
    private Path chosenFolder;
    private boolean busy;
    private boolean undoAvailable;
    private boolean historyReadable;
    private boolean operationFailed;

    /** Creates a window on the Swing event thread using the supplied service. */
    public MainFrame(OrganizerService service) {
        super("Smart File Organizer");
        this.service = service;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(800, 480));
        setSize(1040, 640);
        setLocationRelativeTo(null);

        folder.setEditable(false);
        folder.setToolTipText("The folder whose direct files will be organized");
        var selector = new JPanel(new BorderLayout(12, 0));
        selector.add(new JLabel("Folder"), BorderLayout.WEST);
        selector.add(folder, BorderLayout.CENTER);
        var folderButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        folderButtons.add(choose);
        folderButtons.add(refresh);
        selector.add(folderButtons, BorderLayout.EAST);

        table.setAutoCreateRowSorter(true);
        table.setRowHeight(28);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setMaxWidth(72);
        table.getColumnModel().getColumn(1).setPreferredWidth(180);
        table.getColumnModel().getColumn(3).setPreferredWidth(430);
        table.putClientProperty("terminateEditOnFocusLost", true);

        progress.setStringPainted(true);
        progress.setPreferredSize(new Dimension(180, 24));
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(organize);
        actions.add(undo);
        actions.add(details);
        var bottom = new JPanel(new BorderLayout(12, 12));
        bottom.add(actions, BorderLayout.NORTH);
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(progress, BorderLayout.EAST);

        var content = new JPanel(new BorderLayout(0, 16));
        content.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        content.add(selector, BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);

        choose.addActionListener(event -> chooseFolder());
        refresh.addActionListener(event -> scan());
        organize.addActionListener(event -> organize());
        undo.addActionListener(event -> undo());
        details.addActionListener(event -> showSkipped());
        model.addTableModelListener(event -> updateButtons());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (busy) {
                    JOptionPane.showMessageDialog(MainFrame.this,
                            "Please wait for the current operation to finish.", "Work in progress",
                            JOptionPane.INFORMATION_MESSAGE);
                } else {
                    dispose();
                }
            }
        });
        runTask("Loading saved settings...", true, report -> service.startup(), saved -> {
            if (saved.folder().isPresent()) {
                chosenFolder = saved.folder().get();
                folder.setText(chosenFolder.toString());
                scan();
            } else {
                status.setText(saved.notice().isEmpty() ? "Choose a folder to preview its files." : saved.notice());
            }
        });
    }

    private void chooseFolder() {
        var picker = chosenFolder == null ? new JFileChooser() : new JFileChooser(chosenFolder.toString());
        picker.setDialogTitle("Choose a folder to organize");
        picker.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        picker.setAcceptAllFileFilterUsed(false);
        if (picker.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            chosenFolder = picker.getSelectedFile().toPath();
            folder.setText(chosenFolder.toString());
            scan();
        }
    }

    private void scan() {
        if (chosenFolder == null) return;
        preview = null;
        model.setMoves(java.util.List.of());
        Path selected = chosenFolder;
        runTask("Scanning folder...", true, report -> service.preview(selected), result -> {
            preview = result;
            chosenFolder = result.folder();
            folder.setText(chosenFolder.toString());
            model.setMoves(result.moves());
            selectionStatus();
            if (!result.notice().isEmpty()) {
                JOptionPane.showMessageDialog(this, result.notice(), "Folder setting could not be saved",
                        JOptionPane.WARNING_MESSAGE);
            }
        });
    }

    private void organize() {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        var selected = model.selectedMoves();
        if (preview == null || selected.isEmpty()) return;
        long folders = selected.stream().map(move -> move.destination().getParent()).distinct().count();
        String question = "Move " + selected.size() + " files into " + folders + " folders?";
        if (undoAvailable) question += "\nThis will replace the saved undo history with this organization.";
        if (JOptionPane.showConfirmDialog(this, question, "Confirm organization",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) return;
        Path source = preview.folder();
        clearPreview();
        runTask("Organizing files...", false, report -> service.organize(source, selected, report),
                result -> finish("Organization complete", result, false));
    }

    private void undo() {
        if (JOptionPane.showConfirmDialog(this, "Restore files from the last saved organization?",
                "Confirm undo", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        clearPreview();
        runTask("Restoring files...", false, service::undo, result -> finish("Undo complete", result, true));
    }

    private void clearPreview() {
        preview = null;
        model.setMoves(java.util.List.of());
    }

    private void finish(String title, OrganizeResult result, boolean restoring) {
        String message = (restoring ? "Restored: " : "Moved: ") + result.moved()
                + "\nSkipped: " + result.skipped() + "\nFailed: " + result.failed();
        if (restoring && result.skipped() + result.failed() > 0) {
            message += "\nUnresolved undo entries remain saved. Check for changed files or occupied original names.";
        } else if (!restoring && result.skipped() + result.failed() > 0) {
            message += "\nSome files were unavailable or their destinations changed. Refresh to review the remaining files.";
        }
        LOG.info(title + ": " + result);
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.INFORMATION_MESSAGE);
        status.setText(title);
        if (chosenFolder != null) scan();
    }

    private void showSkipped() {
        if (preview == null || preview.skipped().isEmpty()) return;
        var text = new JTextArea(String.join("\n", preview.skipped()), 16, 65);
        text.setEditable(false);
        text.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(text), "Files excluded from preview",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void selectionStatus() {
        if (preview != null && !busy && !operationFailed) {
            status.setText(model.selectedMoves().size() + " of " + model.getRowCount()
                    + " files selected; " + preview.skipped().size() + " entries excluded.");
        }
    }

    private void updateButtons() {
        choose.setEnabled(!busy);
        refresh.setEnabled(!busy && chosenFolder != null);
        organize.setEnabled(!busy && historyReadable && preview != null && !model.selectedMoves().isEmpty());
        undo.setEnabled(!busy && undoAvailable);
        details.setEnabled(!busy && preview != null && !preview.skipped().isEmpty());
        selectionStatus();
    }

    @FunctionalInterface
    private interface Task<T> {
        T run(IntConsumer report) throws Exception;
    }

    private <T> void runTask(String message, boolean indeterminate, Task<T> task, Consumer<T> completed) {
        busy = true;
        operationFailed = false;
        progress.setString(null);
        LOG.info(message);
        model.setEditable(false);
        status.setText(message);
        progress.setValue(0);
        progress.setIndeterminate(indeterminate);
        updateButtons();
        var worker = new SwingWorker<T, Void>() {
            private boolean savedUndoAvailable;
            private Exception historyFailure;

            @Override protected T doInBackground() throws Exception {
                try {
                    return task.run(this::setProgress);
                } finally {
                    try {
                        savedUndoAvailable = service.hasUndo();
                    } catch (Exception exception) {
                        historyFailure = exception;
                        LOG.log(Level.WARNING, "Cannot refresh saved undo history", exception);
                    }
                }
            }

            @Override protected void done() {
                busy = false;
                undoAvailable = savedUndoAvailable;
                historyReadable = historyFailure == null;
                progress.setIndeterminate(false);
                model.setEditable(true);
                try {
                    T result = get();
                    progress.setValue(100);
                    if (historyFailure != null) showError(historyFailure);
                    completed.accept(result);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showError(exception);
                } catch (ExecutionException exception) {
                    showError(exception.getCause());
                } finally {
                    updateButtons();
                }
            }
        };
        worker.addPropertyChangeListener(event -> {
            if ("progress".equals(event.getPropertyName())) progress.setValue((Integer) event.getNewValue());
        });
        worker.execute();
    }

    private void showError(Throwable exception) {
        operationFailed = true;
        progress.setString("Check the message");
        LOG.log(Level.WARNING, "Organizer operation failed", exception);
        status.setText("Operation could not finish. Refresh the preview or try Undo.");
        String message = FriendlyErrors.message(exception);
        JOptionPane.showMessageDialog(this, message, "Unable to complete operation", JOptionPane.ERROR_MESSAGE);
    }
}
```

### src/main/java/com/smartfileorganizer/core/FileMover.java

```java
package com.smartfileorganizer.core;

import com.smartfileorganizer.model.*;
import com.smartfileorganizer.storage.UndoLogStore;
import com.smartfileorganizer.storage.UndoLogStore.*;
import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Executes selected preview rows and journals each move before it starts. */
public final class FileMover {
    private static final Logger LOG = Logger.getLogger(FileMover.class.getName());
    private final UndoLogStore store;

    /** Uses the same journal as the undo manager. */
    public FileMover(UndoLogStore store) { this.store = store; }

    /** Executes only the supplied preview destinations under an exclusive journal lock. */
    public OrganizeResult organize(Path folder, List<PlannedMove> moves) throws IOException {
        return organize(folder, moves, percent -> {});
    }

    /** Executes approved moves and reports the percentage of rows processed. */
    public OrganizeResult organize(Path folder, List<PlannedMove> moves, IntConsumer progress) throws IOException {
        var selected = List.copyOf(moves);
        Objects.requireNonNull(progress, "progress");
        return store.exclusive(() -> execute(new SafetyChecker().validate(folder), selected, progress));
    }

    private OrganizeResult execute(Path root, List<PlannedMove> moves, IntConsumer progress) throws IOException {
        Batch prior = store.read();
        List<Entry> entries = new ArrayList<>();
        int skipped = 0, failed = 0;
        int processed = 0;
        for (var move : moves) {
            try {
                Path source = move.source(), target = move.destination();
                Entry intent;
                try {
                    validatePaths(root, source, target);
                    if (new CategoryClassifier().classify(source) != move.category()
                            || !source.getParent().equals(root)
                            || !target.getParent().equals(root.resolve(move.category().folderName()))
                            || unavailable(source, store.path()) || Files.exists(target, NOFOLLOW_LINKS)) {
                        skipped++;
                        LOG.info("Skipped unavailable source or occupied destination: " + source);
                        continue;
                    }
                    Path directory = target.getParent();
                    if (Files.notExists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory);
                    checkDirectory(directory);
                    if (!Files.getFileStore(source).equals(Files.getFileStore(directory))) {
                        skipped++;
                        continue;
                    }
                    intent = new Entry(source.toString(), target.toString(), Instant.now().toString(),
                            signature(source), false);
                } catch (IOException | SecurityException | OverlappingFileLockException e) {
                    LOG.log(Level.WARNING, "Cannot prepare move for " + source, e);
                    failed++;
                    continue;
                }
                entries.add(intent);
                store.save(new Batch(root.toString(), entries));
                try {
                    checkDirectory(target.getParent());
                    Files.move(source, target); // No replacement and no cross-volume fallback.
                } catch (FileAlreadyExistsException e) {
                    LOG.info("Destination became occupied: " + target);
                    skipped++;
                    entries.removeLast();
                    store.save(entries.isEmpty() ? prior : new Batch(root.toString(), entries));
                    continue;
                } catch (IOException | SecurityException e) {
                    LOG.log(Level.WARNING, "Cannot move " + source + " to " + target, e);
                    // Preserve an ambiguous outcome for recovery if the source disappeared.
                    if (!Files.exists(source, NOFOLLOW_LINKS)) throw new IOException("Check undo history before continuing.", e);
                    failed++;
                    entries.removeLast();
                    store.save(entries.isEmpty() ? prior : new Batch(root.toString(), entries));
                    continue;
                }
                entries.set(entries.size()-1, new Entry(intent.original(), intent.destination(),
                        intent.timestamp(), intent.signature(), true));
                store.save(new Batch(root.toString(), entries));
            } finally {
                progress.accept((int) (++processed * 100L / moves.size()));
            }
        }
        return new OrganizeResult(entries.size(), skipped, failed);
    }

    static void validatePaths(Path root, Path source, Path target) throws IOException {
        if (!root.equals(source.getParent()) || !source.equals(source.normalize())
                || !target.equals(target.normalize()) || target.getParent() == null
                || !root.resolve(new CategoryClassifier().classify(source).folderName()).equals(target.getParent()))
            throw new IOException("Move paths do not match the selected folder.");
    }

    static void checkDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || !directory.toRealPath().equals(directory))
            throw new IOException("Category folder changed or is linked.");
    }

    static boolean unavailable(Path file, Path journal) throws IOException {
        return FolderScanner.exclusionReason(file, journal,
                Files.exists(journal) ? journal.toRealPath() : journal) != null;
    }

    static String signature(Path file) throws IOException {
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Expected a regular file.");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        try (var input = Files.newInputStream(file, NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return attributes.fileKey() + "/" + attributes.creationTime() + "/" +
                attributes.lastModifiedTime() + "/" + HexFormat.of().formatHex(digest.digest());
    }
}
```

### src/main/java/com/smartfileorganizer/core/UndoManager.java

```java
package com.smartfileorganizer.core;

import com.smartfileorganizer.model.OrganizeResult;
import com.smartfileorganizer.storage.UndoLogStore;
import com.smartfileorganizer.storage.UndoLogStore.Batch;
import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Restores unchanged files from the persisted batch in reverse order. */
public final class UndoManager {
    private static final Logger LOG = Logger.getLogger(UndoManager.class.getName());
    private final UndoLogStore store;

    /** Opens the same journal used by the organizer. */
    public UndoManager(UndoLogStore store) { this.store = store; }

    /** Reports whether there are outstanding journal entries. */
    public boolean hasUndo() throws IOException {
        return !store.read().entries().isEmpty();
    }

    /** Restores eligible files and keeps unresolved entries available for retry. */
    public OrganizeResult undo() throws IOException {
        return undo(percent -> {});
    }

    /** Restores eligible files and reports the percentage of entries processed. */
    public OrganizeResult undo(IntConsumer progress) throws IOException {
        Objects.requireNonNull(progress, "progress");
        return store.exclusive(() -> restore(progress));
    }

    private OrganizeResult restore(IntConsumer progress) throws IOException {
        Batch batch = store.read();
        var entries = new ArrayList<>(batch.entries());
        if (entries.isEmpty()) return new OrganizeResult(0, 0, 0);
        Path root = new SafetyChecker().validate(Path.of(batch.folder()));
        int restored = 0, skipped = 0, failed = 0;
        int total = entries.size();
        for (int i = entries.size() - 1; i >= 0; i--) {
            try {
                var entry = entries.get(i);
                try {
                    Path original = Path.of(entry.original()), moved = Path.of(entry.destination());
                    FileMover.validatePaths(root, original, moved);
                    if (original.equals(store.path()) || moved.equals(store.path())) {
                        throw new IOException("Invalid journal path.");
                    }
                    if (Files.notExists(moved, NOFOLLOW_LINKS) && Files.isRegularFile(original, NOFOLLOW_LINKS)
                            && entry.signature().equals(FileMover.signature(original))) {
                        // The move never ran, or a previous undo completed before its journal update.
                        skipped++;
                    } else if (!Files.notExists(original, NOFOLLOW_LINKS) || Files.notExists(moved, NOFOLLOW_LINKS)) {
                        LOG.info("Undo skipped missing destination or occupied original: " + original);
                        skipped++;
                        continue;
                    } else {
                        FileMover.checkDirectory(moved.getParent());
                        if (FileMover.unavailable(moved, store.path())
                                || !entry.signature().equals(FileMover.signature(moved))
                                || !Files.getFileStore(moved).equals(Files.getFileStore(root))) {
                            LOG.info("Undo skipped changed, unavailable, or relocated file: " + moved);
                            skipped++;
                            continue;
                        }
                        Files.move(moved, original);
                        restored++;
                    }
                } catch (FileAlreadyExistsException e) {
                    LOG.log(Level.INFO, "Original location became occupied during undo", e);
                    skipped++;
                    continue;
                } catch (IOException | SecurityException | InvalidPathException | OverlappingFileLockException e) {
                    LOG.log(Level.WARNING, "Cannot undo entry " + entry.original(), e);
                    failed++;
                    continue;
                }
                entries.remove(i);
                store.save(new Batch(batch.folder(), entries));
            } finally {
                progress.accept((int) ((total - i) * 100L / total));
            }
        }
        return new OrganizeResult(restored, skipped, failed);
    }
}
```

### src/main/java/com/smartfileorganizer/core/FolderScanner.java

```java
package com.smartfileorganizer.core;

import com.smartfileorganizer.model.FileCategory;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Collects eligible direct-child files and destination names for a preview. */
public final class FolderScanner {
    private static final Logger LOG = Logger.getLogger(FolderScanner.class.getName());
    private final SafetyChecker safety = new SafetyChecker();
    private static final Set<String> TEMP_EXTENSIONS = Set.of("crdownload", "part", "tmp");

    /** Creates a scanner with the default safety policy. */
    public FolderScanner() {
    }

    /**
     * Immutable scan snapshot. Skipped entries include reasons for the UI.
     * @param folder canonical source directory
     * @param files eligible sources
     * @param occupied existing names in category folders
     * @param skipped reasons for excluded entries
     */
    public record ScanResult(Path folder, List<Path> files, Set<Path> occupied, List<String> skipped) {
        /** Defensively copies the snapshot collections. */
        public ScanResult {
            files = List.copyOf(files);
            occupied = Set.copyOf(occupied);
            skipped = List.copyOf(skipped);
        }
    }

    /**
     * Scans without moving files or creating category folders.
     * Category directory names are read only to reserve occupied destinations;
     * their contents never become source files and are not scanned recursively.
     * @param folder selected folder
     * @param undoLog exact app undo-log path to exclude
     * @return immutable snapshot for the pure planner
     * @throws IOException if the source or a destination cannot be inspected safely
     */
    public ScanResult scan(Path folder, Path undoLog) throws IOException {
        Path root = safety.validate(folder);
        Path excluded = undoLog.toAbsolutePath().normalize();
        Path realExcluded = Files.exists(excluded) ? excluded.toRealPath() : excluded;
        var files = new ArrayList<Path>();
        var skipped = new ArrayList<String>();
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                try {
                    String reason = exclusionReason(entry, excluded, realExcluded);
                    if (reason == null) {
                        files.add(entry);
                    } else {
                        skipped.add(entry.getFileName() + ": " + reason);
                    }
                } catch (IOException | SecurityException | OverlappingFileLockException exception) {
                    LOG.log(Level.INFO, "Cannot inspect file " + entry, exception);
                    skipped.add(entry.getFileName() + ": unavailable or in use");
                }
            }
        }
        var occupied = new HashSet<Path>();
        for (FileCategory category : FileCategory.values()) {
            Path destination = root.resolve(category.folderName());
            if (Files.notExists(destination, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)
                    || !destination.toRealPath().equals(destination)) {
                throw new IOException("The category folder " + category.folderName() + " is blocked or linked.");
            }
            try (var entries = Files.newDirectoryStream(destination)) {
                entries.forEach(occupied::add);
            }
        }
        files.sort(Comparator.comparing(Path::toString));
        skipped.sort(String::compareTo);
        return new ScanResult(root, files, occupied, skipped);
    }

    static String exclusionReason(Path file, Path excluded, Path realExcluded) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().equals(file)) {
            return "not a direct regular file";
        }
        if (file.equals(excluded) || file.toRealPath().equals(realExcluded)
                || (Files.exists(excluded) && Files.isSameFile(file, excluded))) {
            return "app undo log";
        }
        String name = file.getFileName().toString();
        if (name.startsWith(".") || Files.isHidden(file)) {
            return "hidden file";
        }
        var dos = Files.getFileAttributeView(file, java.nio.file.attribute.DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (dos != null) {
            DosFileAttributes attributes = dos.readAttributes();
            if (attributes.isSystem() || attributes.isHidden() || attributes.isReadOnly()) {
                return "protected file";
            }
        }
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && TEMP_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT))) {
            return "temporary download";
        }
        // Best-effort detection only: other programs may open the file after this check.
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            return lock == null ? "file in use" : null;
        }
    }
}
```

### src/main/java/com/smartfileorganizer/storage/UndoLogStore.java

```java
package com.smartfileorganizer.storage;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import static java.nio.file.StandardOpenOption.*;

/** Saves a complete journal atomically while serializing organizer instances. */
public final class UndoLogStore {
    private final Path path;
    private final Gson json = new Gson();

    /** Selects a journal file, allowing isolated storage during tests. */
    public UndoLogStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    /** Returns the user's standard journal location. */
    public static UndoLogStore forUser() {
        String base = System.getenv("LOCALAPPDATA");
        Path directory = base == null || base.isBlank()
                ? Path.of(System.getProperty("user.home"), ".local", "share") : Path.of(base);
        return new UndoLogStore(directory.resolve("SmartFileOrganizer/undo.json"));
    }

    /** Returns the path to exclude during scanning. */
    public Path path() { return path; }

    /** One durable intention or completed move, with the file's identity and content signature. */
    public record Entry(String original, String destination, String timestamp, String signature, boolean completed) {}

    /** Last batch and its outstanding entries. */
    public record Batch(String folder, List<Entry> entries) {
        /** Prevents accidental modification outside a journal update. */
        public Batch { entries = List.copyOf(entries); }
    }

    /** A journal operation performed under an exclusive process lock. */
    public interface Operation<T> {
        /** Runs the operation with exclusive access. */
        T run() throws IOException;
    }

    /** Executes a whole organize or undo operation under one lock. */
    public <T> T exclusive(Operation<T> operation) throws IOException {
        Files.createDirectories(path.getParent());
        try (var channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"), CREATE, WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another organizer is working.");
            return operation.run();
        } catch (OverlappingFileLockException e) {
            throw new IOException("Another organizer is working.", e);
        }
    }

    /** Loads the last batch or an empty journal on first use. */
    public Batch read() throws IOException {
        if (Files.notExists(path)) return new Batch("", List.of());
        try {
            Batch batch = json.fromJson(Files.readString(path), Batch.class);
            if (batch == null || batch.folder() == null) throw new IOException("Invalid undo history.");
            if (!batch.entries().isEmpty() && !Path.of(batch.folder()).isAbsolute()) {
                throw new IOException("Saved undo history is invalid. Keep the history file for recovery.");
            }
            for (Entry entry : batch.entries()) {
                if (entry == null || entry.original() == null || entry.destination() == null
                        || entry.timestamp() == null || entry.signature() == null || entry.signature().isBlank()
                        || !Path.of(entry.original()).isAbsolute() || !Path.of(entry.destination()).isAbsolute()) {
                    throw new IOException("Saved undo history is invalid. Keep the history file for recovery.");
                }
                java.time.Instant.parse(entry.timestamp());
            }
            return batch;
        } catch (RuntimeException e) {
            throw new IOException("Saved undo history could not be read. Keep the history file for recovery.", e);
        }
    }

    /** Forces a new snapshot to disk before atomically replacing the prior journal. */
    public void save(Batch batch) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), "undo-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(json.toJson(batch));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
```

### src/test/java/com/smartfileorganizer/storage/SettingsStoreTest.java

```java
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
```

### src/test/java/com/smartfileorganizer/storage/UndoLogValidationTest.java

```java
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
```

### src/test/java/com/smartfileorganizer/ui/FriendlyErrorsTest.java

```java
package com.smartfileorganizer.ui;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FriendlyErrorsTest {
    @Test void translatesOperatingSystemErrorsIntoActions() {
        assertTrue(FriendlyErrors.message(new AccessDeniedException("file")).contains("permissions"));
        assertTrue(FriendlyErrors.message(new NoSuchFileException("file")).contains("no longer available"));
        assertTrue(FriendlyErrors.message(new FileAlreadyExistsException("file")).contains("Refresh"));
        assertTrue(FriendlyErrors.message(new FileSystemException("file", null, "No space left on device")).contains("full"));
        assertTrue(FriendlyErrors.message(new FileSystemException("file", null, "File name too long")).contains("too long"));
        assertTrue(FriendlyErrors.message(new FileSystemException("file")).contains("drive connection"));
        assertFalse(FriendlyErrors.message(new IllegalStateException("internal stack details")).contains("internal stack"));
    }
}
```

### src/test/java/com/smartfileorganizer/ui/DesktopFlowTest.java

```java
package com.smartfileorganizer.ui;

import com.formdev.flatlaf.FlatLightLaf;
import com.smartfileorganizer.core.OrganizerService;
import com.smartfileorganizer.storage.UndoLogStore;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class DesktopFlowTest {
    @TempDir Path temp;

    @Test void chooseSortExcludeOrganizeAndUndoThroughWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Path source = Files.createDirectory(temp.resolve("Downloads"));
        Files.writeString(source.resolve("a.txt"), "alpha");
        Files.writeString(source.resolve("b.jpg"), "beta");
        var service = new OrganizerService(new UndoLogStore(temp.resolve("state/undo.json")));
        MainFrame frame = onEdt(() -> {
            FlatLightLaf.setup();
            var window = new MainFrame(service);
            window.setVisible(true);
            return window;
        });
        try {
            await(() -> button(frame, "Choose folder").isEnabled());
            assertFalse(onEdt(() -> button(frame, "Organize").isEnabled()));
            assertFalse(onEdt(() -> button(frame, "Undo last organization").isEnabled()));
            click(frame, "Choose folder");
            await(() -> visible(JFileChooser.class) != null);
            onEdt(() -> {
                JFileChooser picker = visible(JFileChooser.class);
                picker.setSelectedFile(source.toFile());
                picker.approveSelection();
                return null;
            });
            await(() -> button(frame, "Organize").isEnabled());
            JTable table = onEdt(() -> find(frame, JTable.class));
            assertEquals(2, onEdt(table::getRowCount));
            onEdt(() -> {
                table.getRowSorter().toggleSortOrder(1);
                table.getRowSorter().toggleSortOrder(1);
                assertEquals("b.jpg", table.getValueAt(0, 1));
                table.setValueAt(false, 0, 0);
                var image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
                frame.paint(image.getGraphics());
                Files.createDirectories(Path.of("target"));
                ImageIO.write(image, "png", Path.of("target/phase5-preview.png").toFile());
                return null;
            });
            click(frame, "Organize");
            confirm();
            await(() -> visible(JOptionPane.class) != null);
            assertEquals("alpha", Files.readString(source.resolve("Documents/a.txt")));
            assertEquals("beta", Files.readString(source.resolve("b.jpg")));
            assertFalse(Files.exists(source.resolve("a.txt")));
            dismiss();
            await(() -> button(frame, "Undo last organization").isEnabled()
                    && button(frame, "Choose folder").isEnabled());
            click(frame, "Undo last organization");
            confirm();
            await(() -> visible(JOptionPane.class) != null);
            assertEquals("alpha", Files.readString(source.resolve("a.txt")));
            dismiss();
            await(() -> button(frame, "Choose folder").isEnabled());
            assertFalse(onEdt(() -> button(frame, "Undo last organization").isEnabled()));
            onEdt(() -> { frame.dispose(); return null; });
            MainFrame reopened = onEdt(() -> {
                var window = new MainFrame(new OrganizerService(new UndoLogStore(temp.resolve("state/undo.json"))));
                window.setVisible(true);
                return window;
            });
            await(() -> button(reopened, "Organize").isEnabled());
            assertEquals(2, onEdt(() -> find(reopened, JTable.class).getRowCount()));
        } finally {
            onEdt(() -> {
                for (Window window : Window.getWindows()) window.dispose();
                return null;
            });
        }
    }

    @Test void progressIncludesEverySelectedAndUndoneFile() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("a.txt"), "a");
        Files.writeString(source.resolve("b.txt"), "b");
        var service = new OrganizerService(new UndoLogStore(temp.resolve("state/undo.json")));
        var plan = service.preview(source);
        var progress = new ArrayList<Integer>();
        assertEquals(2, service.organize(plan.folder(), plan.moves(), progress::add).moved());
        assertEquals(java.util.List.of(50, 100), progress);
        progress.clear();
        assertEquals(2, service.undo(progress::add).moved());
        assertEquals(java.util.List.of(50, 100), progress);
    }

    private static void confirm() throws Exception {
        await(() -> visible(JOptionPane.class) != null);
        dismiss();
    }

    private static void dismiss() throws Exception {
        onEdt(() -> { visible(JOptionPane.class).setValue(JOptionPane.OK_OPTION); return null; });
        // Flush events after the modal event loop exits.
        onEdt(() -> null);
    }

    private static void click(Container parent, String label) {
        SwingUtilities.invokeLater(() -> button(parent, label).doClick());
    }

    private static JButton button(Container parent, String label) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JButton button && label.equals(button.getText())) return button;
            if (component instanceof Container container) {
                JButton found = button(container, label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends Component> T visible(Class<T> type) {
        for (Window window : Window.getWindows()) {
            if (window.isShowing()) {
                T found = find(window, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends Component> T find(Container parent, Class<T> type) {
        for (Component component : parent.getComponents()) {
            if (type.isInstance(component)) return type.cast(component);
            if (component instanceof Container container) {
                T found = find(container, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!onEdt(condition)) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for the desktop interface.");
            Thread.sleep(25);
        }
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        var value = new AtomicReference<T>();
        var failure = new AtomicReference<Exception>();
        SwingUtilities.invokeAndWait(() -> {
            try { value.set(action.call()); }
            catch (Exception exception) { failure.set(exception); }
        });
        if (failure.get() != null) throw failure.get();
        return value.get();
    }
}
```

## 4. How to run and verify
With Maven and JDK 21 installed, run from the project directory:
```powershell
mvn clean verify
java -jar target/smart-file-organizer.jar
```
Expected on a Windows desktop: 91 tests, zero failures, then BUILD SUCCESS. The desktop integration test opens temporary windows and uses temporary files. The desktop test is skipped in headless environments.

Run the already compiled application with the installed JDK:
```powershell
& "C:\Program Files\Java\jdk-25.0.4.1\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp "target/phase6;target/gson.jar;target/flatlaf.jar" com.smartfileorganizer.Main
```
Choose a throwaway folder and wait for its preview. Close the idle window, reopen it, and check that the same folder is previewed automatically. The automated desktop test verifies this behavior.
Verified with JDK 25 using --release 21 and the standalone JUnit runner. A full run of 90 tests passed. The subsequently added journal-validation regression and all five existing move/undo tests passed in a targeted run. Maven packaging and execution on JDK 21 remain unverified.

## 5. How it works
SettingsStore saves versioned Gson JSON next to the undo journal. On Windows these files normally live under %LOCALAPPDATA%\SmartFileOrganizer. It forces a temporary snapshot to disk before replacing settings atomically. Settings are saved only after a successful scan and preview.
Startup loads and validates the last folder in a background worker. Missing, protected, or malformed saved settings return the interface to folder selection with a notice. Settings-save failures leave a usable preview and show a warning. Settings operations leave the undo journal separate.
FriendlyErrors translates permission, missing-file, occupied-destination, disk-space, and path-length exceptions into actions users can take. Unknown details stay in the application log. The window prevents Organize while undo history cannot be read. Malformed persisted undo entries block file operations while preserving the history file.
Main configures three rotating log files of about 1 MB each, reports logging setup failures, and records unexpected uncaught errors. Core operations now log per-file inspection, move, and undo failures. Result dialogs explain unresolved undo entries and remaining files.
The automatic startup preview is an implementation choice for remembering the last folder. It still requires explicit selection and confirmation before organization.

## 6. Common errors
- Saved-folder notice. The folder may have moved, become unavailable, or been protected. Choose a valid folder again.
- Folder cannot be remembered. Check app-data permissions and free disk space. You can still use the current preview.
- Undo history cannot be read. Keep undo.json for recovery. The interface disables Organize to protect the existing history.
- Log cannot be opened. Check permissions and free space in the app-data directory. Console logging remains available.
- Maven not recognized. Install Maven and add its bin directory to PATH, or use the compiled-version command above.
