package io.jenkins.plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Confines a workspace-relative path to the workspace on the agent's file system.
 *
 * <p>{@link CogniumCli#validateWorkspaceRelativePath} rejects absolute paths, {@code ..} and option-like
 * values as strings. This class runs on the agent and checks what the path actually resolves to: every
 * existing component that is a symbolic link must resolve inside the workspace, a dangling link is rejected
 * (writing the report through it would create a file wherever it points), and an existing target's real path
 * must be inside the workspace.
 *
 * <p>Uses only {@code java.nio} so it can be unit-tested without a Jenkins controller.
 */
final class WorkspacePaths {

    private WorkspacePaths() {}

    /**
     * Resolves {@code relativePath} against {@code workspaceRoot} and fails if it, or any symbolic link on
     * the way to it, leaves the workspace.
     *
     * @return the resolved (normalized, not link-resolved) path
     * @throws IOException with a message naming {@code field} when the path escapes the workspace
     */
    static Path confine(Path workspaceRoot, String relativePath, String field) throws IOException {
        Path root = workspaceRoot.toRealPath();

        Path candidate;
        try {
            candidate = root.resolve(relativePath).normalize();
        } catch (InvalidPathException e) {
            throw new IOException("Invalid Cognium " + field + ": " + relativePath, e);
        }
        if (!candidate.startsWith(root)) {
            throw outside(field, relativePath);
        }

        Path current = root;
        for (Path name : root.relativize(candidate)) {
            if (name.toString().isEmpty()) {
                continue;
            }
            current = current.resolve(name);
            if (Files.isSymbolicLink(current)) {
                if (!Files.exists(current)) {
                    throw new IOException(
                            "Cognium " + field + " goes through a dangling symbolic link: " + relativePath);
                }
                Path target = current.toRealPath();
                if (!target.startsWith(root)) {
                    throw new IOException("Cognium " + field
                            + " goes through a symbolic link that leaves the Jenkins workspace: " + relativePath);
                }
                current = target;
            } else if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                // Nothing below a missing component exists yet, so it cannot hold a link.
                break;
            }
        }

        if (Files.exists(candidate) && !candidate.toRealPath().startsWith(root)) {
            throw outside(field, relativePath);
        }
        return candidate;
    }

    private static IOException outside(String field, String relativePath) {
        return new IOException("Cognium " + field + " resolves outside the Jenkins workspace: " + relativePath);
    }
}
