package com.taskpilot.agent.tools;

import com.taskpilot.agent.config.AgentProperties;
import com.taskpilot.agent.executor.LocalProcessCommandExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceToolsTest {

    @TempDir
    Path tempDir;

    private WorkspaceTools tools;

    @BeforeEach
    void setUp() throws IOException {
        AgentProperties props = new AgentProperties();
        props.setMaxReadLines(5);
        props.setMaxCommandOutputLines(3);
        tools = new WorkspaceTools(tempDir, new LocalProcessCommandExecutor(), props);

        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/Hello.java"),
                "line1\nline2\nline3\nline4\nline5\nline6\nline7\n");
    }

    @Test
    void listFilesAndSearchGrepWorkInsideWorkspace() {
        String listing = tools.listFiles(".", 3);
        assertThat(listing).contains("src/").contains("src/Hello.java");

        String grep = tools.searchGrep("line4", ".");
        assertThat(grep).contains("src/Hello.java:4: line4");
    }

    @Test
    void readFileRespectsMaxLineWindow() {
        String content = tools.readFile("src/Hello.java", 1, 20);
        assertThat(content).contains("1: line1").contains("5: line5");
        assertThat(content).doesNotContain("6: line6");
        assertThat(content).contains("2 more lines");
    }

    @Test
    void editFileReplacesUniqueMatchAndRejectsMissingMatch() {
        String ok = tools.editFile("src/Hello.java", "line3\nline4", "line3_updated\nline4");
        assertThat(ok).startsWith("OK:");

        String readBack = tools.readFile("src/Hello.java", 1, 5);
        assertThat(readBack).contains("3: line3_updated");

        String missing = tools.editFile("src/Hello.java", "does_not_exist", "x");
        assertThat(missing).startsWith("ERROR: oldText not found");
    }

    @Test
    void runCommandExecutesAndTruncatesLongOutput() {
        String output = tools.runCommand("printf 'a\\nb\\nc\\nd\\ne\\n'", 10);
        assertThat(output).contains("exitCode=0");
        assertThat(output).contains("truncated first 2 lines");
        assertThat(output).contains("c").contains("d").contains("e");
    }
}
