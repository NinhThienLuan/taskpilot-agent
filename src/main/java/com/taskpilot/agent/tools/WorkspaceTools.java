package com.taskpilot.agent.tools;

import com.taskpilot.agent.config.AgentProperties;
import com.taskpilot.agent.executor.CommandExecutor;
import com.taskpilot.agent.model.CommandResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Per-job Spring AI toolset bound to a single workspace directory.
 * Always instantiate a fresh {@code WorkspaceTools} per job to prevent cross-job state collisions.
 */
public class WorkspaceTools {

    private final PathGuard pathGuard;
    private final CommandExecutor commandExecutor;
    private final AgentProperties properties;

    public WorkspaceTools(Path workspaceRoot, CommandExecutor commandExecutor, AgentProperties properties) {
        this.pathGuard = new PathGuard(workspaceRoot);
        this.commandExecutor = commandExecutor;
        this.properties = properties;
    }

    @Tool(name = "list_files", description = "List files and directories inside the workspace. Ignores .git, target, build, node_modules.")
    public String listFiles(
            @ToolParam(description = "Relative directory path (use '.' for workspace root)") String relativePath,
            @ToolParam(description = "Maximum directory depth to traverse, e.g. 3") Integer maxDepth
    ) {
        try {
            Path startDir = pathGuard.resolveForRead(relativePath);
            if (!Files.exists(startDir)) {
                return "ERROR: Directory does not exist: " + relativePath;
            }
            if (!Files.isDirectory(startDir)) {
                return "ERROR: Path is not a directory: " + relativePath;
            }
            int depthLimit = (maxDepth == null || maxDepth < 1) ? 3 : Math.min(maxDepth, 8);
            List<String> entries = new ArrayList<>();
            int maxEntries = 200;

            Files.walkFileTree(startDir, java.util.Set.of(), depthLimit, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(startDir) && pathGuard.isIgnoredDirectory(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(startDir)) {
                        entries.add(pathGuard.toRelativeUnixPath(dir) + "/");
                    }
                    return entries.size() >= maxEntries ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    entries.add(pathGuard.toRelativeUnixPath(file));
                    return entries.size() >= maxEntries ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }
            });

            if (entries.isEmpty()) {
                return "(empty directory)";
            }
            String result = String.join("\n", entries);
            if (entries.size() >= maxEntries) {
                result += "\n[... truncated at " + maxEntries + " entries ...]";
            }
            return result;
        } catch (SecurityException | IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(name = "read_file", description = "Read a file with 1-based line numbers. Keep ranges concise (default max 200 lines per call).")
    public String readFile(
            @ToolParam(description = "Relative path to the file") String relativePath,
            @ToolParam(description = "1-based start line (inclusive, optional)") Integer startLine,
            @ToolParam(description = "1-based end line (inclusive, optional)") Integer endLine
    ) {
        try {
            Path file = pathGuard.resolveForRead(relativePath);
            if (!Files.exists(file) || Files.isDirectory(file)) {
                return "ERROR: File not found: " + relativePath;
            }
            List<String> allLines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (allLines.isEmpty()) {
                return "(empty file)";
            }

            int totalLines = allLines.size();
            int start = (startLine == null || startLine < 1) ? 1 : Math.min(startLine, totalLines);
            int maxWindow = properties.getMaxReadLines();
            int requestedEnd = (endLine == null || endLine < start) ? (start + maxWindow - 1) : endLine;
            int end = Math.min(totalLines, Math.min(requestedEnd, start + maxWindow - 1));

            StringBuilder sb = new StringBuilder();
            sb.append("File: ").append(pathGuard.toRelativeUnixPath(file))
                    .append(" (lines ").append(start).append("-").append(end)
                    .append(" of ").append(totalLines).append(")\n");
            for (int i = start; i <= end; i++) {
                sb.append(i).append(": ").append(allLines.get(i - 1)).append("\n");
            }
            if (end < totalLines) {
                sb.append("[... ").append(totalLines - end)
                        .append(" more lines; call read_file with startLine=")
                        .append(end + 1).append(" to continue ...]");
            }
            return sb.toString();
        } catch (SecurityException | IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(name = "search_grep", description = "Search for a text or regex pattern across files in the workspace.")
    public String searchGrep(
            @ToolParam(description = "Text or regex pattern to search for") String pattern,
            @ToolParam(description = "Relative directory or file path to search in (use '.' for all)") String relativePath
    ) {
        if (pattern == null || pattern.isBlank()) {
            return "ERROR: pattern must not be blank";
        }
        try {
            Pattern compiled = Pattern.compile(pattern);
            Path startPath = pathGuard.resolveForRead(relativePath);
            if (!Files.exists(startPath)) {
                return "ERROR: Path does not exist: " + relativePath;
            }

            List<String> matches = new ArrayList<>();
            int maxMatches = properties.getMaxGrepMatches();

            Files.walkFileTree(startPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(startPath) && pathGuard.isIgnoredDirectory(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.size() > 1_000_000) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        String rel = pathGuard.toRelativeUnixPath(file);
                        for (int i = 0; i < lines.size(); i++) {
                            if (compiled.matcher(lines.get(i)).find()) {
                                matches.add(rel + ":" + (i + 1) + ": " + lines.get(i).trim());
                                if (matches.size() >= maxMatches) {
                                    return FileVisitResult.TERMINATE;
                                }
                            }
                        }
                    } catch (IOException ignored) {
                        // Skip binary or unreadable files
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (matches.isEmpty()) {
                return "No matches found for pattern: " + pattern;
            }
            String result = String.join("\n", matches);
            if (matches.size() >= maxMatches) {
                result += "\n[... truncated at " + maxMatches + " matches ...]";
            }
            return result;
        } catch (PatternSyntaxException e) {
            return "ERROR: Invalid regex pattern: " + e.getMessage();
        } catch (SecurityException | IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(name = "edit_file", description = "Replace one exact occurrence of oldText with newText in an existing file.")
    public String editFile(
            @ToolParam(description = "Relative path to the existing file") String relativePath,
            @ToolParam(description = "Exact text block to find (must match exactly once)") String oldText,
            @ToolParam(description = "Replacement text") String newText
    ) {
        if (oldText == null || oldText.isEmpty()) {
            return "ERROR: oldText must not be empty. Use write_file to create a new file.";
        }
        try {
            Path file = pathGuard.resolveForWrite(relativePath);
            if (!Files.exists(file) || Files.isDirectory(file)) {
                return "ERROR: File does not exist: " + relativePath;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int firstIdx = content.indexOf(oldText);
            if (firstIdx < 0) {
                return "ERROR: oldText not found in " + relativePath
                        + ". Call read_file to inspect exact lines before retrying.";
            }
            int secondIdx = content.indexOf(oldText, firstIdx + oldText.length());
            if (secondIdx >= 0) {
                return "ERROR: oldText matched multiple locations in " + relativePath
                        + ". Include more surrounding lines in oldText to make it unique.";
            }
            String updated = content.substring(0, firstIdx)
                    + (newText == null ? "" : newText)
                    + content.substring(firstIdx + oldText.length());
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            return "OK: Updated " + pathGuard.toRelativeUnixPath(file);
        } catch (SecurityException | IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(name = "write_file", description = "Create a new file or overwrite an existing file inside the workspace.")
    public String writeFile(
            @ToolParam(description = "Relative path to the file") String relativePath,
            @ToolParam(description = "Full file content to write") String content
    ) {
        try {
            Path file = pathGuard.resolveForWrite(relativePath);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
            return "OK: Wrote " + pathGuard.toRelativeUnixPath(file);
        } catch (SecurityException | IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(name = "run_command", description = "Run a build, test, or linter shell command inside the workspace and return truncated output.")
    public String runCommand(
            @ToolParam(description = "Shell command to execute, e.g. 'mvn test'") String command,
            @ToolParam(description = "Optional timeout in seconds (default 90)") Integer timeoutSec
    ) {
        if (command == null || command.isBlank()) {
            return "ERROR: command must not be blank";
        }
        int effectiveTimeout = (timeoutSec == null || timeoutSec < 1)
                ? properties.getCommandTimeoutSeconds()
                : Math.min(timeoutSec, 300);

        CommandResult result = commandExecutor.execute(
                pathGuard.workspaceRoot(),
                command,
                Duration.ofSeconds(effectiveTimeout)
        );
        String truncated = truncateTailLines(result.combinedOutput(), properties.getMaxCommandOutputLines());
        return "exitCode=" + result.exitCode()
                + ", timedOut=" + result.timedOut()
                + ", durationMs=" + result.durationMs()
                + "\n" + truncated;
    }

    @Tool(name = "git_status_diff", description = "Inspect current git status and git diff inside the workspace (read-only).")
    public String gitStatusDiff(
            @ToolParam(description = "If true, return only 'git diff --stat'; if false, return full diff") Boolean statOnly
    ) {
        String cmd = Boolean.TRUE.equals(statOnly)
                ? "git status --short && echo '--- DIFF STAT ---' && git diff --stat"
                : "git status --short && echo '--- DIFF ---' && git diff";
        CommandResult result = commandExecutor.execute(
                pathGuard.workspaceRoot(),
                cmd,
                Duration.ofSeconds(20)
        );
        return truncateTailLines(result.combinedOutput(), properties.getMaxCommandOutputLines());
    }

    public static String truncateTailLines(String text, int maxLines) {
        if (text == null || text.isEmpty()) {
            return "(no output)";
        }
        String[] lines = text.split("\\R");
        if (lines.length <= maxLines) {
            return text;
        }
        int omitted = lines.length - maxLines;
        StringBuilder sb = new StringBuilder();
        sb.append("[... truncated first ").append(omitted).append(" lines ...]\n");
        for (int i = omitted; i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }
}
