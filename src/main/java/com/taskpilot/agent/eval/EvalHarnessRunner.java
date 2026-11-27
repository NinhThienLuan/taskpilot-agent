package com.taskpilot.agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.agent.config.AgentProperties;
import com.taskpilot.agent.core.CodingAgent;
import com.taskpilot.agent.executor.CommandExecutor;
import com.taskpilot.agent.executor.SandboxLifecycle;
import com.taskpilot.agent.harness.AntiRewardHackingChecker;
import com.taskpilot.agent.model.AgentResult;
import com.taskpilot.agent.model.CommandResult;
import com.taskpilot.agent.model.TaskRequest;
import com.taskpilot.agent.model.TokenUsageSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Isolated Trial Runner + 3-Layer Grader (Regression, Anti-Reward-Hacking, Hidden Tests)
 * for benchmarking TaskPilot across LLM profiles and prompt iterations.
 */
@Service
public class EvalHarnessRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalHarnessRunner.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final CodingAgent codingAgent;
    private final CommandExecutor commandExecutor;
    private final SandboxLifecycle sandboxLifecycle;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;

    public EvalHarnessRunner(
            CodingAgent codingAgent,
            CommandExecutor commandExecutor,
            SandboxLifecycle sandboxLifecycle,
            AgentProperties agentProperties,
            ObjectMapper objectMapper
    ) {
        this.codingAgent = codingAgent;
        this.commandExecutor = commandExecutor;
        this.sandboxLifecycle = sandboxLifecycle;
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
    }

    public List<EvalCase> loadCases(Path casesJsonPath) throws IOException {
        return objectMapper.readValue(casesJsonPath.toFile(), new TypeReference<>() {});
    }

    public EvalReport runSuite(String profile, List<EvalCase> cases, Path projectRoot) {
        List<EvalCaseResult> results = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            log.info("=== Running Eval Case {} ({}) ===", evalCase.id(), evalCase.difficulty());
            results.add(runSingleCase(evalCase, projectRoot));
        }
        return EvalReport.from(profile, results);
    }

    public EvalCaseResult runSingleCase(EvalCase evalCase, Path projectRoot) {
        long startNanos = System.nanoTime();
        Path tempWorkspace = null;
        try {
            tempWorkspace = Files.createTempDirectory("taskpilot-eval-" + evalCase.id() + "-");
            Path templateDir = projectRoot.resolve(evalCase.repoTemplate()).normalize();
            copyDirectoryExcludingBuild(templateDir, tempWorkspace);

            // Establish clean git baseline so AntiRewardHackingChecker can inspect exact agent diff
            commandExecutor.execute(
                    tempWorkspace,
                    "git init && git config user.email eval@taskpilot.local && git config user.name TaskPilotEval && git add -A && git commit -m baseline",
                    Duration.ofSeconds(20)
            );

            try (AutoCloseable ignored = sandboxLifecycle.openSession(evalCase.id(), tempWorkspace)) {
                TaskRequest request = new TaskRequest(
                        evalCase.id(),
                        evalCase.taskDescription(),
                        evalCase.extraPrompt(),
                        tempWorkspace,
                        evalCase.testCommand()
                );

                AgentResult agentResult = codingAgent.runCodingLoop(request);

                // Grader 1: Regression test suite
                CommandResult regResult = commandExecutor.execute(
                        tempWorkspace, evalCase.testCommand(), agentProperties.getCommandTimeout()
                );
                boolean regPassed = regResult.isSuccess();

                // Grader 2: Anti-Reward-Hacking diff inspection
                CommandResult diffResult = commandExecutor.execute(
                        tempWorkspace, "git diff HEAD", Duration.ofSeconds(15)
                );
                var hackCheck = AntiRewardHackingChecker.inspectUnifiedDiff(diffResult.stdout());
                boolean hygieneClean = hackCheck.clean();

                // Grader 3: Hidden grading tests (injected ONLY AFTER agent finishes)
                boolean hiddenPassed = true;
                String hiddenDetails = "";
                if (evalCase.hiddenTestDir() != null && !evalCase.hiddenTestDir().isBlank()) {
                    Path hiddenDir = projectRoot.resolve(evalCase.hiddenTestDir()).normalize();
                    if (Files.exists(hiddenDir)) {
                        copyDirectoryExcludingBuild(hiddenDir, tempWorkspace);
                        CommandResult hiddenResult = commandExecutor.execute(
                                tempWorkspace, evalCase.testCommand(), agentProperties.getCommandTimeout()
                        );
                        hiddenPassed = hiddenResult.isSuccess();
                        if (!hiddenPassed) {
                            hiddenDetails = hiddenResult.combinedOutput();
                        }
                    }
                }

                long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
                EvalCaseResult.FailureReason reason = classifyFailure(
                        agentResult.status(), regPassed, hygieneClean, hiddenPassed
                );
                boolean passed = reason == EvalCaseResult.FailureReason.NONE;

                return new EvalCaseResult(
                        evalCase.id(),
                        evalCase.difficulty(),
                        evalCase.category(),
                        passed,
                        reason,
                        regPassed,
                        hygieneClean,
                        hiddenPassed,
                        agentResult.iterations(),
                        durationMs,
                        agentResult.tokenUsage(),
                        passed ? "All graders passed" : (hackCheck.clean() ? hiddenDetails : hackCheck.summary())
                );
            }
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.error("Eval case {} failed with exception", evalCase.id(), e);
            return new EvalCaseResult(
                    evalCase.id(),
                    evalCase.difficulty(),
                    evalCase.category(),
                    false,
                    EvalCaseResult.FailureReason.AGENT_FAILED,
                    false,
                    false,
                    false,
                    0,
                    durationMs,
                    TokenUsageSummary.ZERO,
                    e.getMessage()
            );
        } finally {
            if (tempWorkspace != null) {
                deleteRecursivelyQuietly(tempWorkspace);
            }
        }
    }

    public Path writeReportFiles(EvalReport report, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        String stamp = TS_FMT.format(report.generatedAt());
        String baseName = "eval-report-" + report.profile().replaceAll("[^a-zA-Z0-9-]", "_") + "-" + stamp;

        Path jsonFile = outputDir.resolve(baseName + ".json");
        Path mdFile = outputDir.resolve(baseName + ".md");

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonFile.toFile(), report);
        Files.writeString(mdFile, report.toMarkdown(), StandardCharsets.UTF_8);
        return mdFile;
    }

    private static EvalCaseResult.FailureReason classifyFailure(
            AgentResult.Status agentStatus,
            boolean regPassed,
            boolean hygieneClean,
            boolean hiddenPassed
    ) {
        if (!hygieneClean) {
            return EvalCaseResult.FailureReason.REWARD_HACKING;
        }
        if (agentStatus == AgentResult.Status.TOKEN_BUDGET_EXCEEDED) {
            return EvalCaseResult.FailureReason.TOKEN_BUDGET_EXCEEDED;
        }
        if (agentStatus == AgentResult.Status.STUCK_LOOP) {
            return EvalCaseResult.FailureReason.STUCK_LOOP;
        }
        if (agentStatus == AgentResult.Status.MAX_ITERATIONS_REACHED && !hiddenPassed) {
            return EvalCaseResult.FailureReason.MAX_ITERATIONS;
        }
        if (!regPassed) {
            return EvalCaseResult.FailureReason.REGRESSION_FAIL;
        }
        if (!hiddenPassed) {
            return EvalCaseResult.FailureReason.HIDDEN_TEST_FAIL;
        }
        if (agentStatus == AgentResult.Status.FAILED) {
            return EvalCaseResult.FailureReason.AGENT_FAILED;
        }
        return EvalCaseResult.FailureReason.NONE;
    }

    private static void copyDirectoryExcludingBuild(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path name = dir.getFileName();
                if (!dir.equals(source) && name != null
                        && (name.toString().equals("target") || name.toString().equals(".git"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursivelyQuietly(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Best-effort temp cleanup
        }
    }
}
