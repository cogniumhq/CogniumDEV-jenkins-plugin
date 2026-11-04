package io.jenkins.plugins;

import hudson.Util;
import hudson.util.ArgumentListBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

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
     * Default {@code cognium-dev} version: an exact release, so a build never installs whatever was
     * published last. Bump it deliberately in a plugin release. Pipelines can set {@code packageVersion}
     * to another exact version, or to a dist-tag such as {@code latest} to opt in to following npm.
     */
    public static final String DEFAULT_PACKAGE_VERSION = "4.17.0";

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
    private static final Pattern EXACT_VERSION =
            Pattern.compile("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$");

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
        // cognium-dev and its dependencies (circle-ir, web-tree-sitter, yaml,
        // @cognium/project-profile-detect) declare no install-time scripts, so none are run.
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
        String trimmed = Util.fixEmptyAndTrim(format);
        return trimmed == null ? DEFAULT_FORMAT : trimmed;
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

    /** True when {@code packageVersion} names one release (for example {@code 4.17.0}), not a tag or range. */
    static boolean isExactVersion(String packageVersion) {
        return packageVersion != null && EXACT_VERSION.matcher(packageVersion.trim()).matches();
    }

    /**
     * Reads {@code version} from the installed {@code node_modules/cognium-dev/package.json}.
     *
     * @throws IllegalArgumentException when the manifest is not JSON or has no version
     */
    static String installedVersion(String packageJson) {
        String version = null;
        try {
            JSONObject manifest = JSONObject.fromObject(packageJson);
            if (PACKAGE_NAME.equals(manifest.optString("name", null))) {
                version = Util.fixEmptyAndTrim(manifest.optString("version", null));
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException("The installed " + PACKAGE_NAME + " package.json is not valid JSON.", e);
        }
        if (version == null) {
            throw new IllegalArgumentException(
                    "The installed package.json does not name " + PACKAGE_NAME + " with a version.");
        }
        return version;
    }

    /**
     * Checks that npm installed the requested release. An exact {@code requested} version must match
     * {@code installed}; a tag or range cannot be checked and is accepted.
     *
     * @throws IllegalArgumentException when an exact version was requested and another was installed
     */
    static void requireInstalledVersion(String requested, String installed) {
        if (isExactVersion(requested) && !requested.trim().equals(installed)) {
            throw new IllegalArgumentException("npm installed " + PACKAGE_NAME + "@" + installed + " but "
                    + requested.trim() + " was requested.");
        }
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
