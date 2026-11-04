package io.jenkins.plugins;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.util.ArgumentListBuilder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import jenkins.MasterToSlaveFileCallable;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;

public class CogniumStepExecution extends SynchronousNonBlockingStepExecution<Void> {

    private static final long serialVersionUID = 1L;

    private final String path;
    private final String packageVersion;
    private final String format;
    private final String output;
    private final String severity;
    private final String category;
    private final boolean failOnFindings;

    CogniumStepExecution(CogniumStep step, StepContext context) {
        super(context);
        this.path = step.getPath();
        this.packageVersion = step.getPackageVersion();
        this.format = step.getFormat();
        this.output = step.getOutput();
        this.severity = step.getSeverity();
        this.category = step.getCategory();
        this.failOnFindings = step.isFailOnFindings();
    }

    @Override
    protected Void run() throws Exception {
        FilePath workspace = getContext().get(FilePath.class);
        Launcher launcher = getContext().get(Launcher.class);
        TaskListener listener = getContext().get(TaskListener.class);
        EnvVars env = getContext().get(EnvVars.class);

        if (workspace == null) {
            throw new AbortException("Cognium requires a Jenkins workspace (run the step inside a node block).");
        }
        if (launcher == null) {
            throw new AbortException("Cognium requires a Jenkins launcher.");
        }
        if (listener == null) {
            throw new AbortException("Cognium requires a Jenkins task listener.");
        }
        if (env == null) {
            env = new EnvVars();
        }

        ensureWorkspace(workspace);
        validatePathsInWorkspace(workspace);

        FilePath tempDirectory = null;
        try {
            listener.getLogger().println("[Cognium] Preparing Cognium SAST scan...");
            listener.getLogger().println("[Cognium] path=" + path);
            listener.getLogger().println("[Cognium] format=" + format);
            listener.getLogger().println("[Cognium] packageVersion=" + packageVersion);
            listener.getLogger().println("[Cognium] failOnFindings=" + failOnFindings);

            verifyNode(launcher, workspace, env, listener);

            tempDirectory = createInstallDirectory(workspace);
            installCognium(tempDirectory, launcher, workspace, env, listener);

            int exitCode = runScan(tempDirectory, launcher, workspace, env, listener);
            handleScanExitCode(exitCode, failOnFindings, listener);
            return null;
        } finally {
            cleanup(tempDirectory, listener);
        }
    }

    private static void ensureWorkspace(FilePath workspace) throws IOException, InterruptedException {
        if (workspace.exists()) {
            return;
        }
        workspace.mkdirs();
        if (!workspace.exists()) {
            throw new AbortException("Cognium could not create workspace directory: " + workspace.getRemote());
        }
    }

    private void validatePathsInWorkspace(FilePath workspace) throws IOException, InterruptedException {

        String scanPath = CogniumCli.defaultedPath(path);
        String outputPath = CogniumCli.optionalOutput(output);

        workspace.act(new MasterToSlaveFileCallable<Void>() {

            private static final long serialVersionUID = 1L;

            @Override
            public Void invoke(File workspaceRoot, VirtualChannel channel) throws IOException {

                Path root = workspaceRoot.getCanonicalFile().toPath();

                validatePath(root, scanPath, "scan path");

                if (outputPath != null) {
                    validatePath(root, outputPath, "report output");
                }

                return null;
            }

            private void validatePath(Path root, String relativePath, String field) throws IOException {

                File candidate = new File(root.toFile(), relativePath);
                Path resolved = candidate.getCanonicalFile().toPath();

                if (!resolved.startsWith(root)) {
                    throw new IOException(
                            "Cognium " + field + " resolves outside the Jenkins workspace: " + relativePath);
                }
            }
        });
    }

    private static FilePath createInstallDirectory(FilePath workspace) throws IOException, InterruptedException {
        FilePath parent = workspace.getParent();
        FilePath base = parent != null ? parent : workspace;
        return base.createTempDir("cognium", "dir");
    }

    private void verifyNode(Launcher launcher, FilePath workspace, EnvVars env, TaskListener listener)
            throws IOException, InterruptedException {

        listener.getLogger()
                .println("[Cognium] Checking Node.js >= " + CogniumCli.MINIMUM_NODE_VERSION + " is available...");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exitCode;

        try {
            if (launcher.isUnix()) {
                ArgumentListBuilder nodeCommand = new ArgumentListBuilder();
                nodeCommand.add("/bin/sh");
                nodeCommand.add("-c");
                nodeCommand.add("exec node -v");

                exitCode = launcher.launch()
                        .pwd(workspace)
                        .cmds(nodeCommand)
                        .envs(env)
                        .stdout(stdout)
                        .stderr(listener.getLogger())
                        .join();
            } else {
                exitCode = launcher.launch()
                        .pwd(workspace)
                        .cmds(CogniumCli.processArguments(false, CogniumCli.nodeVersionCheckCommand()))
                        .envs(env)
                        .stdout(stdout)
                        .stderr(listener.getLogger())
                        .join();
            }
        } catch (IOException e) {
            throw new AbortException("Node.js is required on the Jenkins agent but was not found in PATH. "
                    + "Install Node.js >= "
                    + CogniumCli.MINIMUM_NODE_VERSION
                    + " and ensure it is available on PATH. "
                    + detail(e));
        }

        if (exitCode != 0) {
            throw new AbortException("Unable to determine the Node.js version on the Jenkins agent. "
                    + "The 'node -v' command exited with code "
                    + exitCode
                    + ".");
        }

        String version = stdout.toString(StandardCharsets.UTF_8).trim();

        if (!CogniumCli.isSupportedNodeVersion(version)) {
            throw new AbortException("Node.js "
                    + (version.isBlank() ? "<unknown>" : version)
                    + " is not supported. Cognium requires Node.js >= "
                    + CogniumCli.MINIMUM_NODE_VERSION
                    + ".");
        }

        listener.getLogger().println("[Cognium] Node.js " + version + " detected.");
    }

    private void installCognium(
            FilePath tempDirectory, Launcher launcher, FilePath workspace, EnvVars env, TaskListener listener)
            throws IOException, InterruptedException {
        listener.getLogger()
                .println("[Cognium] Installing " + CogniumCli.PACKAGE_NAME + "@" + packageVersion
                        + " into a temporary directory (not the global npm prefix)...");

        int exitCode;
        try {
            exitCode = launch(
                    launcher,
                    workspace,
                    env,
                    listener,
                    CogniumCli.installCommand(launcher.isUnix(), tempDirectory.getRemote(), packageVersion));
        } catch (IOException e) {
            throw new AbortException("Failed to run npm. Install Node.js/npm on the Jenkins agent and ensure npm is on "
                    + "PATH. "
                    + detail(e));
        }
        if (exitCode != 0) {
            throw new AbortException("Failed to install " + CogniumCli.PACKAGE_NAME + "@" + packageVersion
                    + ". npm exited with code "
                    + exitCode
                    + ".");
        }

        FilePath executable = scanExecutablePath(tempDirectory, launcher.isUnix());
        if (!executable.exists()) {
            throw new AbortException(
                    "cognium-dev was installed but the executable was not found at " + executable.getRemote() + ".");
        }
    }

    private int runScan(
            FilePath tempDirectory, Launcher launcher, FilePath workspace, EnvVars env, TaskListener listener)
            throws IOException, InterruptedException {
        listener.getLogger().println("[Cognium] Running SAST scan...");
        try {
            return launch(
                    launcher,
                    workspace,
                    env,
                    listener,
                    CogniumCli.scanCommand(
                            launcher.isUnix(), tempDirectory.getRemote(), path, format, output, severity, category));
        } catch (IOException e) {
            throw new AbortException("Failed to execute cognium-dev scan. " + detail(e));
        }
    }

    static void handleScanExitCode(int exitCode, boolean failOnFindings, TaskListener listener) throws AbortException {
        if (exitCode == CogniumCli.EXIT_SUCCESS) {
            listener.getLogger().println("[Cognium] Scan completed successfully (no security findings).");
            return;
        }
        if (exitCode == CogniumCli.EXIT_FINDINGS) {
            if (!failOnFindings) {
                listener.getLogger().println("[Cognium] Security findings detected.");
                listener.getLogger().println("[Cognium] failOnFindings=false; continuing the Jenkins build.");
                return;
            }
            throw new AbortException("Cognium detected security findings.");
        }
        throw new AbortException("Cognium scan failed with exit code " + exitCode
                + " (scanner or operational error). This fails the build regardless of failOnFindings.");
    }

    private static FilePath scanExecutablePath(FilePath tempDirectory, boolean unix) {
        FilePath bin = tempDirectory.child("node_modules").child(".bin");
        return unix ? bin.child("cognium-dev") : bin.child("cognium-dev.cmd");
    }

    private static int launch(
            Launcher launcher, FilePath workspace, EnvVars env, TaskListener listener, List<String> command)
            throws IOException, InterruptedException {

        ArgumentListBuilder args;

        if (launcher.isUnix() && !command.isEmpty() && "npm".equals(command.get(0))) {

            // Resolve npm using the PATH supplied by the Jenkins Pipeline.
            // This also allows the test harness to select its fake npm.
            args = new ArgumentListBuilder();
            args.add("/bin/sh");
            args.add("-c");
            args.add("exec \"$@\"");
            args.add("sh");

            for (String argument : command) {
                args.add(argument);
            }

        } else {
            args = CogniumCli.processArguments(launcher.isUnix(), command);
        }

        return launcher.launch()
                .pwd(workspace)
                .cmds(args)
                .envs(env)
                .stdout(listener)
                .stderr(listener.getLogger())
                .join();
    }

    private static void cleanup(FilePath tempDirectory, TaskListener listener) {
        if (tempDirectory == null) {
            return;
        }
        try {
            if (tempDirectory.exists()) {
                tempDirectory.deleteRecursive();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.getLogger().println("[Cognium] Warning: cleanup interrupted: " + e.getMessage());
        } catch (IOException e) {
            listener.getLogger()
                    .println("[Cognium] Warning: failed to clean up temporary directory: " + e.getMessage());
        }
    }

    private static String detail(IOException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
