package io.jenkins.plugins;

import hudson.Util;
import hudson.util.ArgumentListBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds argument lists for a local, prefix-scoped {@code cognium-dev} install and scan.
 *
 * <p>Command construction is isolated from process launching so Windows vs Unix executable names
 * can be unit-tested without a Jenkins controller.
 *
 * <p>{@code cognium-dev} documents scan exit codes {@code 0} (no security findings), {@code 1}
 * (security findings), and {@code 2} (analysis error). Those values are centralized here so they
 * can be updated independently of Jenkins plumbing.
 *
 * <p>The public CLI accepts {@code cognium-dev scan <path> [options]} (positional path), not
 * {@code --path}.
 */
public final class CogniumCli {

    public static final String PACKAGE_NAME = "cognium-dev";

    /**
     * Default npm dist-tag. {@code latest} always installs the current published {@code cognium-dev}
     * release. Pipelines can pin a concrete version when they need a reproducible scanner.
     */
    public static final String DEFAULT_PACKAGE_VERSION = "latest";

    public static final String DEFAULT_PATH = ".";
    public static final String DEFAULT_FORMAT = "text";

    public static final int EXIT_SUCCESS = 0;
    public static final int EXIT_FINDINGS = 1;

    static final String MINIMUM_NODE_VERSION = "20.19.0";
    private static final int MINIMUM_NODE_MAJOR = 20;
    private static final int MINIMUM_NODE_MINOR = 19;
    private static final int MINIMUM_NODE_PATCH = 0;

    private static final Pattern NODE_VERSION = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$");

    private static final Pattern SAFE_NPM_VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+-]*$");
    private static final Pattern SAFE_CLI_VALUE = Pattern.compile("^[A-Za-z0-9._,+-]+$");

    private CogniumCli() {}

    public static String npmExecutable(boolean unix) {
        return unix ? "npm" : "npm.cmd";
    }

    public static String nodeExecutable() {
        // "node" on both platforms so Windows PATHEXT can resolve node.exe / node.cmd via cmd.exe.
        return "node";
    }

    public static String scanExecutable(boolean unix, String installDirRemote) {
        if (unix) {
            return installDirRemote + "/node_modules/.bin/cognium-dev";
        }
        return installDirRemote + "\\node_modules\\.bin\\cognium-dev.cmd";
    }

    public static List<String> nodeVersionCheckCommand() {
        List<String> command = new ArrayList<>();
        command.add(nodeExecutable());
        command.add("-v");
        return command;
    }

    public static List<String> installCommand(boolean unix, String installDirRemote, String packageVersion) {
        List<String> command = new ArrayList<>();
        command.add(npmExecutable(unix));
        command.add("install");
        command.add("--prefix");
        command.add(installDirRemote);
        command.add("--no-save");
        command.add("--ignore-scripts");
        command.add(PACKAGE_NAME + "@" + requireSafeNpmVersion(packageVersion));
        return command;
    }

    public static List<String> scanCommand(
            boolean unix,
            String installDirRemote,
            String path,
            String format,
            String output,
            String severity,
            String category) {
        List<String> command = new ArrayList<>();
        command.add(scanExecutable(unix, installDirRemote));
        command.add("scan");
        command.add(defaultedPath(path));
        command.add("--format");
        command.add(defaultedFormat(format));
        addOptionalFlag(command, "--output", output);
        addOptionalFlag(command, "--severity", severity);
        addOptionalFlag(command, "--category", category);
        return command;
    }

    /**
     * Prepares a process argument list.
     *
     * <p>On Windows, {@code node} is wrapped with {@code cmd.exe /c} so PATHEXT can resolve
     * {@code node.exe} or {@code node.cmd}. {@code npm.cmd} and {@code cognium-dev.cmd} are left as
     * explicit {@code .cmd} paths so the JVM can launch them without extra quoting around
     * {@code --prefix} directories that contain spaces.
     */
    public static ArgumentListBuilder processArguments(boolean unix, List<String> command) {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add(command);
        if (!unix && !command.isEmpty() && nodeExecutable().equals(command.get(0))) {
            return args.toWindowsCommand();
        }
        return args;
    }

    static String validateWorkspaceRelativePath(String value, String fieldName) {
        String trimmed = Util.fixEmptyAndTrim(value);

        if (trimmed == null) {
            return null;
        }

        // Reject paths beginning with CLI options.
        if (trimmed.startsWith("-")) {
            throw new IllegalArgumentException("Invalid Cognium " + fieldName + ": paths must not start with '-'.");
        }

        // Reject control characters.
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                throw new IllegalArgumentException(
                        "Invalid Cognium " + fieldName + ": control characters are not allowed.");
            }
        }

        // Reject Unix absolute paths, Windows rooted paths and UNC paths.
        if (trimmed.startsWith("/") || trimmed.startsWith("\\") || trimmed.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Invalid Cognium " + fieldName + ": absolute paths are not allowed.");
        }

        // Reject Windows alternate data streams and drive syntax.
        if (trimmed.contains(":")) {
            throw new IllegalArgumentException("Invalid Cognium " + fieldName + ": ':' is not allowed.");
        }

        // Check both separator styles on every operating system.
        String[] segments = trimmed.split("[/\\\\]", -1);

        for (String segment : segments) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("Invalid Cognium " + fieldName + ": '..' traversal is not allowed.");
            }
        }

        return trimmed;
    }

    static String defaultedPath(String path) {
        String validated = validateWorkspaceRelativePath(path, "path");
        return validated == null ? DEFAULT_PATH : validated;
    }

    static String defaultedFormat(String format) {
        String value = Util.fixEmptyAndTrim(format);

        if (value == null) {
            return DEFAULT_FORMAT;
        }

        return optionalCliValue(value, "format");
    }

    static String defaultedPackageVersion(String packageVersion) {
        String trimmed = Util.fixEmptyAndTrim(packageVersion);
        return trimmed == null ? DEFAULT_PACKAGE_VERSION : requireSafeNpmVersion(trimmed);
    }

    static String optionalCliValue(String value, String fieldName) {
        String trimmed = Util.fixEmptyAndTrim(value);
        if (trimmed == null) {
            return null;
        }
        if (!SAFE_CLI_VALUE.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid Cognium " + fieldName + ": " + trimmed);
        }
        return trimmed;
    }

    static String optionalOutput(String output) {
        return validateWorkspaceRelativePath(output, "output");
    }

    static String requireSafeNpmVersion(String packageVersion) {
        String trimmed = Util.fixEmptyAndTrim(packageVersion);
        if (trimmed == null || !SAFE_NPM_VERSION.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid cognium-dev packageVersion: " + packageVersion);
        }
        if (trimmed.toLowerCase(Locale.ROOT).contains("cognium-dev")) {
            throw new IllegalArgumentException("packageVersion must be an npm version or tag, not a package name");
        }
        return trimmed;
    }

    static boolean isSupportedNodeVersion(String version) {
        if (version == null) {
            return false;
        }

        String trimmed = version.trim();

        Matcher matcher = NODE_VERSION.matcher(trimmed);
        if (!matcher.matches()) {
            return false;
        }

        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = Integer.parseInt(matcher.group(3));

        if (major != MINIMUM_NODE_MAJOR) {
            return major > MINIMUM_NODE_MAJOR;
        }

        if (minor != MINIMUM_NODE_MINOR) {
            return minor > MINIMUM_NODE_MINOR;
        }

        return patch >= MINIMUM_NODE_PATCH;
    }

    private static void addOptionalFlag(List<String> command, String flag, String value) {
        String trimmed = Util.fixEmptyAndTrim(value);
        if (trimmed != null) {
            command.add(flag);
            command.add(trimmed);
        }
    }
}
