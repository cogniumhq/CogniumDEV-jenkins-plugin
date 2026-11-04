package io.jenkins.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacePathsTest {

    @TempDir
    Path temp;

    private Path workspace;
    private Path outside;

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectories(temp.resolve("workspace"));
        outside = Files.createDirectories(temp.resolve("outside"));
        Files.createDirectories(workspace.resolve("src/main"));
    }

    @Test
    void acceptsPathsInsideTheWorkspace() throws IOException {
        Path root = workspace.toRealPath();
        assertEquals(root, WorkspacePaths.confine(workspace, ".", "scan path"));
        assertEquals(root.resolve("src/main"), WorkspacePaths.confine(workspace, "src/main", "scan path"));
        assertEquals(
                root.resolve("reports/new/out.sarif"),
                WorkspacePaths.confine(workspace, "reports/new/out.sarif", "report output"));
        assertEquals(root.resolve("src"), WorkspacePaths.confine(workspace, "src/main/..", "scan path"));
    }

    @Test
    void rejectsTraversalOutOfTheWorkspace() {
        IOException error =
                assertThrows(IOException.class, () -> WorkspacePaths.confine(workspace, "../outside", "scan path"));
        assertTrue(error.getMessage().contains("outside the Jenkins workspace"), error.getMessage());
        assertThrows(
                IOException.class, () -> WorkspacePaths.confine(workspace, "src/../../outside/r.sarif", "report output"));
    }

    @Test
    void rejectsAbsolutePathsOutsideTheWorkspace() {
        assertThrows(
                IOException.class,
                () -> WorkspacePaths.confine(workspace, outside.toAbsolutePath().toString(), "scan path"));
    }

    @Test
    void rejectsSymlinkThatEscapesTheWorkspace() throws IOException {
        Path link = symlink(workspace.resolve("escape"), outside);
        IOException error =
                assertThrows(IOException.class, () -> WorkspacePaths.confine(workspace, "escape", "scan path"));
        assertTrue(error.getMessage().contains("symbolic link"), error.getMessage());
        assertThrows(
                IOException.class,
                () -> WorkspacePaths.confine(workspace, "escape/new-dir/report.sarif", "report output"));
        assertTrue(Files.isSymbolicLink(link));
    }

    @Test
    void rejectsNestedSymlinkThatEscapesTheWorkspace() throws IOException {
        symlink(workspace.resolve("src/main/up"), outside);
        assertThrows(IOException.class, () -> WorkspacePaths.confine(workspace, "src/main/up/x", "scan path"));
    }

    @Test
    void rejectsDanglingSymlinkForTheReport() throws IOException {
        symlink(workspace.resolve("report.sarif"), outside.resolve("not-yet-written.sarif"));
        IOException error = assertThrows(
                IOException.class, () -> WorkspacePaths.confine(workspace, "report.sarif", "report output"));
        assertTrue(error.getMessage().contains("dangling"), error.getMessage());
    }

    @Test
    void acceptsSymlinkThatStaysInsideTheWorkspace() throws IOException {
        symlink(workspace.resolve("code"), workspace.resolve("src"));
        assertEquals(
                workspace.toRealPath().resolve("code/main"), WorkspacePaths.confine(workspace, "code/main", "scan path"));
    }

    @Test
    void acceptsWorkspaceReachedThroughASymlink() throws IOException {
        Path linkedWorkspace = symlink(temp.resolve("linked-workspace"), workspace);
        assertEquals(
                workspace.toRealPath().resolve("src"), WorkspacePaths.confine(linkedWorkspace, "src", "scan path"));
    }

    private static Path symlink(Path link, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            // Windows agents without the symlink privilege cannot run these cases.
            assumeTrue(false, "symbolic links are not available: " + e.getMessage());
            throw e;
        }
    }
}
