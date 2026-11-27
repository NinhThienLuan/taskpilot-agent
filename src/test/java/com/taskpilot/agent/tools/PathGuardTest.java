package com.taskpilot.agent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void allowsRelativePathsInsideWorkspace() {
        PathGuard guard = new PathGuard(tempDir);
        Path resolved = guard.resolveForWrite("src/main/App.java");
        assertThat(resolved).isEqualTo(tempDir.resolve("src/main/App.java").normalize());
    }

    @Test
    void blocksPathTraversalOutsideWorkspace() {
        PathGuard guard = new PathGuard(tempDir);
        assertThatThrownBy(() -> guard.resolveForRead("../secret.txt"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("escapes workspace");
    }

    @Test
    void blocksAbsolutePaths() {
        PathGuard guard = new PathGuard(tempDir);
        assertThatThrownBy(() -> guard.resolveForRead("/etc/passwd"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Absolute paths");
    }

    @Test
    void blocksGitMetadataAndEnvFileEdits() {
        PathGuard guard = new PathGuard(tempDir);
        assertThatThrownBy(() -> guard.resolveForWrite(".git/config"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining(".git");

        assertThatThrownBy(() -> guard.resolveForWrite(".env"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining(".env");

        assertThatThrownBy(() -> guard.resolveForWrite("config/.env.local"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining(".env");
    }
}
