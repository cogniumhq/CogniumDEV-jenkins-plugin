package io.jenkins.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.AbortException;
import hudson.model.TaskListener;
import java.util.List;
import org.junit.jupiter.api.Test;

class CogniumCliTest {

    @Test
    void unixNpmAndScanExecutables() {
        assertEquals("npm", CogniumCli.npmExecutable(true));
        assertEquals("/tmp/install/node_modules/.bin/cognium-dev", CogniumCli.scanExecutable(true, "/tmp/install"));
    }

    @Test
    void windowsNpmAndScanExecutables() {
        assertEquals("npm.cmd", CogniumCli.npmExecutable(false));
        assertEquals(
                "C:\\temp\\install\\node_modules\\.bin\\cognium-dev.cmd",
                CogniumCli.scanExecutable(false, "C:\\temp\\install"));
    }

    @Test
    void installCommandUsesPrefixAndDoesNotSaveGlobally() {
        List<String> command = CogniumCli.installCommand(true, "/tmp/c", "latest");
        assertEquals(List.of("npm", "install", "--prefix", "/tmp/c", "--no-save", "cognium-dev@latest"), command);
    }

    @Test
    void scanCommandUsesPositionalPathLikeTheCli() {
        List<String> command =
                CogniumCli.scanCommand(true, "/tmp/c", "src", "json", "reports/out.sarif", "high", "security");
        assertEquals("scan", command.get(1));
        assertEquals("src", command.get(2));
        assertFalse(command.contains("--path"));
        assertEquals(
                List.of(
                        "/tmp/c/node_modules/.bin/cognium-dev",
                        "scan",
                        "src",
                        "--format",
                        "json",
                        "--output",
                        "reports/out.sarif",
                        "--severity",
                        "high",
                        "--category",
                        "security"),
                command);
    }

    @Test
    void windowsCommandsAreWrappedWithCmd() {
        List<String> wrapped =
                CogniumCli.processArguments(false, List.of("node", "-v")).toList();
        assertEquals("cmd.exe", wrapped.get(0));
        assertFalse(CogniumCli.processArguments(false, List.of("npm.cmd", "install"))
                .toList()
                .get(0)
                .equals("cmd.exe"));
        assertTrue(wrapped.contains("/C")
                || wrapped.contains("/c")
                || wrapped.stream().anyMatch(s -> s.contains("node")));
    }

    @Test
    void nodeVersionCheckUsesDashV() {
        assertEquals(List.of("node", "-v"), CogniumCli.nodeVersionCheckCommand());
    }

    @Test
    void defaultsPathAndFormat() {
        assertEquals(".", CogniumCli.defaultedPath("  "));
        assertEquals("text", CogniumCli.defaultedFormat(null));
        assertEquals("latest", CogniumCli.defaultedPackageVersion(null));
    }

    @Test
    void rejectsUnsafePackageVersion() {
        assertThrows(IllegalArgumentException.class, () -> CogniumCli.requireSafeNpmVersion("1.0.0; rm -rf /"));
        assertThrows(IllegalArgumentException.class, () -> CogniumCli.requireSafeNpmVersion("../../../evil"));
    }

    @Test
    void findingsDoNotFailByDefault() throws Exception {
        CogniumStepExecution.handleScanExitCode(1, false, TaskListener.NULL);
    }

    @Test
    void findingsFailWhenRequested() {
        AbortException error = assertThrows(
                AbortException.class, () -> CogniumStepExecution.handleScanExitCode(1, true, TaskListener.NULL));
        assertTrue(error.getMessage().contains("security findings"));
    }

    @Test
    void operationalErrorsAlwaysFail() {
        AbortException error = assertThrows(
                AbortException.class, () -> CogniumStepExecution.handleScanExitCode(2, false, TaskListener.NULL));
        assertTrue(error.getMessage().contains("exit code 2"));
    }

    @Test
    void supportedNodeVersionsAreAccepted() {
        assertTrue(CogniumCli.isSupportedNodeVersion("v20.19.0"));
        assertTrue(CogniumCli.isSupportedNodeVersion("20.19.0"));
        assertTrue(CogniumCli.isSupportedNodeVersion("v20.20.0"));
        assertTrue(CogniumCli.isSupportedNodeVersion("v21.0.0"));
        assertTrue(CogniumCli.isSupportedNodeVersion("v22.14.0"));
    }

    @Test
    void unsupportedNodeVersionsAreRejected() {
        assertFalse(CogniumCli.isSupportedNodeVersion("v18.20.0"));
        assertFalse(CogniumCli.isSupportedNodeVersion("v19.9.0"));
        assertFalse(CogniumCli.isSupportedNodeVersion("v20.18.0"));
        assertFalse(CogniumCli.isSupportedNodeVersion("v20.18.99"));
    }

    @Test
    void malformedNodeVersionsAreRejected() {
        assertFalse(CogniumCli.isSupportedNodeVersion(null));
        assertFalse(CogniumCli.isSupportedNodeVersion(""));
        assertFalse(CogniumCli.isSupportedNodeVersion("node"));
        assertFalse(CogniumCli.isSupportedNodeVersion("20"));
    }

    @Test
    void rejectsWindowsAbsoluteAndTraversalPaths() {
        assertThrows(IllegalArgumentException.class, () -> CogniumCli.defaultedPath("C:\\outside"));

        assertThrows(IllegalArgumentException.class, () -> CogniumCli.defaultedPath("\\\\server\\share\\outside"));

        assertThrows(IllegalArgumentException.class, () -> CogniumCli.defaultedPath("..\\outside"));

        assertThrows(IllegalArgumentException.class, () -> CogniumCli.defaultedPath("foo\\..\\outside"));

        assertThrows(IllegalArgumentException.class, () -> CogniumCli.defaultedPath("-rf"));
    }

    @Test
    void rejectsWindowsAbsoluteAndTraversalOutputs() {
        assertThrows(IllegalArgumentException.class, () -> CogniumCli.optionalOutput("C:\\outside\\report.sarif"));

        assertThrows(IllegalArgumentException.class, () -> CogniumCli.optionalOutput("..\\outside\\report.sarif"));

        assertThrows(
                IllegalArgumentException.class, () -> CogniumCli.optionalOutput("\\\\server\\share\\report.sarif"));

        assertThrows(IllegalArgumentException.class, () -> CogniumCli.optionalOutput("-output"));
    }

    @Test
    void rejectsUnixAbsoluteAndTraversalPaths() {
        for (String bad : List.of("/etc", "/var/lib/jenkins/workspace/other", "..", "../other-job", "src/../../x")) {
            assertThrows(IllegalArgumentException.class, () -> CogniumCli.defaultedPath(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> CogniumCli.optionalOutput(bad), bad);
        }
    }

    @Test
    void rejectsOptionLikePathsAndOutputs() {
        for (String bad : List.of("-", "--config=evil.json", "-o")) {
            assertThrows(IllegalArgumentException.class, () -> CogniumCli.defaultedPath(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> CogniumCli.optionalOutput(bad), bad);
        }
    }

    @Test
    void rejectsCmdMetacharactersInPathsAndOutputs() {
        for (String bad : List.of("src&calc", "src|x", "a>b", "a<b", "a^b", "%PATH%", "a!b", "a\"b", "a`b")) {
            assertThrows(IllegalArgumentException.class, () -> CogniumCli.defaultedPath(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> CogniumCli.optionalOutput(bad), bad);
        }
    }

    @Test
    void acceptsRelativePathsInsideTheWorkspace() {
        assertEquals("src/main", CogniumCli.defaultedPath("src/main"));
        assertEquals("my dir/app", CogniumCli.defaultedPath("my dir/app"));
        assertEquals("reports/out.sarif", CogniumCli.optionalOutput("reports/out.sarif"));
        assertEquals("a..b", CogniumCli.defaultedPath("a..b"));
    }
}
