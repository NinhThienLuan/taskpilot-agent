package com.taskpilot.agent.tools;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Enforces workspace containment (blocks path traversal) and prevents modifications
 * to sensitive files such as .git internals or .env files.
 */
public class PathGuard {

    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", "target", "build", "node_modules", ".venv", "venv", ".idea", ".gradle"
    );

    private final Path workspaceRoot;

    public PathGuard(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public Path resolveForRead(String relativePath) {
        Path resolved = resolveContained(relativePath);
        if (isInsideGitMetadata(resolved)) {
            throw new SecurityException("Access to .git metadata is blocked: " + relativePath);
        }
        return resolved;
    }

    public Path resolveForWrite(String relativePath) {
        Path resolved = resolveContained(relativePath);
        if (isInsideGitMetadata(resolved)) {
            throw new SecurityException("Modifying .git metadata is blocked: " + relativePath);
        }
        String fileName = resolved.getFileName() != null
                ? resolved.getFileName().toString().toLowerCase(Locale.ROOT)
                : "";
        if (fileName.equals(".env") || fileName.startsWith(".env.")) {
            throw new SecurityException("Modifying .env files is blocked: " + relativePath);
        }
        return resolved;
    }

    public boolean isIgnoredDirectory(Path dir) {
        Path fileName = dir.getFileName();
        return fileName != null && IGNORED_DIRECTORIES.contains(fileName.toString());
    }

    public String toRelativeUnixPath(Path path) {
        return workspaceRoot.relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private Path resolveContained(String relativePath) {
        String safeRel = (relativePath == null || relativePath.isBlank()) ? "." : relativePath.trim();
        Path candidate = Path.of(safeRel);
        if (candidate.isAbsolute()) {
            throw new SecurityException("Absolute paths are not allowed: " + relativePath);
        }
        Path resolved = workspaceRoot.resolve(candidate).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new SecurityException("Path escapes workspace boundary: " + relativePath);
        }
        return resolved;
    }

    private boolean isInsideGitMetadata(Path resolved) {
        Path rel = workspaceRoot.relativize(resolved);
        return rel.getNameCount() > 0 && ".git".equals(rel.getName(0).toString());
    }
}
