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
        List<String> command = CogniumCli.installCommand(true, "/tmp/c", "4.17.0");
        assertEquals(
                List.of("npm", "install", "--prefix", "/tmp/c", "--no-save", "--ignore-scripts", "cognium-dev@4.17.0"),
                command);
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
        assertEquals(CogniumCli.DEFAULT_PACKAGE_VERSION, CogniumCli.defaultedPackageVersion(null));
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
    void installNeverRunsPackageScripts() {
        for (boolean unix : new boolean[] {true, false}) {
            for (String version : List.of("4.17.0", "latest")) {
                assertTrue(CogniumCli.installCommand(unix, "/tmp/c", version).contains("--ignore-scripts"));
            }
        }
    }

    @Test
    void defaultPackageVersionIsAnExactRelease() {
        assertTrue(CogniumCli.isExactVersion(CogniumCli.DEFAULT_PACKAGE_VERSION));
        assertFalse(CogniumCli.DEFAULT_PACKAGE_VERSION.equals("latest"));
    }

    @Test
    void exactVersionsAreRecognised() {
        assertTrue(CogniumCli.isExactVersion("4.17.0"));
        assertTrue(CogniumCli.isExactVersion("5.0.0-rc.1"));
        assertFalse(CogniumCli.isExactVersion("latest"));
        assertFalse(CogniumCli.isExactVersion("4"));
        assertFalse(CogniumCli.isExactVersion("4.17"));
        assertFalse(CogniumCli.isExactVersion("4.x"));
        assertFalse(CogniumCli.isExactVersion(null));
    }

    @Test
    void installedVersionIsReadFromThePackageManifest() {
        assertEquals("4.17.0", CogniumCli.installedVersion("{\"name\":\"cognium-dev\",\"version\":\"4.17.0\"}"));
        assertThrows(IllegalArgumentException.class, () -> CogniumCli.installedVersion("not json"));
        assertThrows(IllegalArgumentException.class, () -> CogniumCli.installedVersion("{\"name\":\"cognium-dev\"}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CogniumCli.installedVersion("{\"name\":\"other\",\"version\":\"4.17.0\"}"));
    }

    @Test
    void exactRequestedVersionMustMatchTheInstall() {
        CogniumCli.requireInstalledVersion("4.17.0", "4.17.0");
        CogniumCli.requireInstalledVersion("latest", "4.19.0");
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> CogniumCli.requireInstalledVersion("4.17.0", "4.19.0"));
        assertTrue(error.getMessage().contains("4.19.0"), error.getMessage());
        assertTrue(error.getMessage().contains("4.17.0"), error.getMessage());
    }
}
