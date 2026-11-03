# Contributing to Smart File Organizer

Small fixes, clear bug reports, documentation improvements, and focused pull requests are welcome.

## Set up the project

Use JDK 21 and Maven. Fork the repository, clone your fork using its GitHub **Code** button, and create a branch for your change.

From the project directory:

```powershell
java -version
mvn -version
mvn clean verify
java -jar target/smart-file-organizer.jar
```

Use temporary files or disposable copies for every manual organization and undo check.

## Understand the structure

| Location | Responsibility |
| --- | --- |
| `model/` | Categories and immutable move/result values |
| `core/` | Classification, safety, scanning, planning, moving, undo, and the application service |
| `storage/` | Settings and persistent undo history |
| `ui/` | Swing window, table model, and friendly error messages |
| `src/test/java/` | JUnit 5 tests, including temporary-file and desktop flows |
| `scripts/` | Windows packaging and packaged-launcher verification |
| `.github/workflows/` | Build verification and tag-triggered releases |

All application packages are under `com.smartfileorganizer`.

## File-safety requirements

- Use Java NIO paths and file operations. Keep Swing imports in the UI layer.
- Keep move planning deterministic and free of filesystem access.
- Show planned destinations before executing moves.
- Preserve destination collision checks and per-file failure handling.
- Persist each move intention before starting the move, then record completion.
- Preserve pending journal entries when the outcome is uncertain.
- Undo only when the moved file matches its saved fingerprint and the original path is free.
- Retain unresolved undo entries for retry.
- Preserve settings and journal compatibility, or provide an explicit migration.
- Run disk work outside the Swing event thread.
- Keep journal access serialized across application instances.
- Keep the default extension mapping in `FileCategory`.

Discuss changes involving file operations or journal semantics in an issue before starting a large implementation.

## Tests

Run:

```powershell
mvn clean verify
```

Use JUnit 5 and `@TempDir` for filesystem tests. Add regression coverage for a concrete bug or a new behavior. Cover partial failure and retry when changing moves or undo.

The desktop integration test briefly opens windows and uses temporary data. It verifies folder selection, sorting, row exclusion, organization, undo, and reopening the saved folder. The GUI test is skipped in headless environments.

Tests for DOS hidden/system/read-only attributes run on Windows. The Windows desktop suite currently contains 98 tests.

## Pull requests

Keep each pull request focused. Explain the user-visible change, the reason for it, and how you verified it. Include screenshots for interface changes and mention any compatibility or recovery implications.

Add Javadoc for public classes and methods. Prefer readable code, small methods, and existing project helpers. Avoid introducing libraries unless they solve a concrete need.

Generated `target/`, `dist/`, and `.build-tools/` directories are excluded from Git. Commit source files, documentation, and intentional screenshot assets.

Contributions are provided under this project's MIT License. Retain upstream copyright and license notices for reused third-party code.

## Report a bug

Use the bug report form in the repository's **Issues** tab. Include the app version, Windows version, launch method, expected behavior, actual behavior, and a reproducible example using dummy files.

For a move or undo failure, preserve the journal and affected files. Share filenames and a directory outline after removing private information. Logs and `undo.json` can contain full paths, so review attachments before posting.

## Suggest a feature

Use the feature request form and describe the user's problem, a concrete example, and the expected preview/undo behavior. Custom rules, recursive scanning, scheduling, cloud features, and a dark-mode toggle are outside v1 and need a scope discussion.

## Maintainer release steps

1. Review the current verification limits in the README.
2. Set the POM version and confirm the numeric version follows Windows installer limits.
3. Run `mvn clean verify` on a Windows desktop using Java 21.
4. Build with `scripts/package-windows.ps1` and review the packaged-launcher result.
5. Check that the JAR contains the project license and dependency notices.
6. Verify installer installation, launch, uninstall, and upgrade behavior on disposable Windows 10/11 environments.
7. Run **Windows release** manually on GitHub to review its artifacts.
8. Push the matching version tag when the release is ready. For numeric version 1.0.0, the tag is `v1.0.0`.

Pushing a version tag starts the release workflow and publishes files after a successful build. The workflow's publication step requires repository content-write permission. Retain the fixed Windows upgrade UUID across releases.
