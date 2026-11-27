package com.taskpilot.agent.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "taskpilot.eval", name = "enabled", havingValue = "true")
public class EvalCliRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalCliRunner.class);

    private final EvalHarnessRunner evalHarnessRunner;
    private final Environment environment;
    private final String casesFile;
    private final String reportDir;
    private final String caseIdFilter;

    public EvalCliRunner(
            EvalHarnessRunner evalHarnessRunner,
            Environment environment,
            @Value("${taskpilot.eval.cases-file:eval/cases/cases.json}") String casesFile,
            @Value("${taskpilot.eval.report-dir:eval-reports}") String reportDir,
            @Value("${taskpilot.eval.case-id:}") String caseIdFilter
    ) {
        this.evalHarnessRunner = evalHarnessRunner;
        this.environment = environment;
        this.casesFile = casesFile;
        this.reportDir = reportDir;
        this.caseIdFilter = caseIdFilter;
    }

    @Override
    public void run(String... args) throws Exception {
        Path projectRoot = Path.of(".").toAbsolutePath().normalize();
        List<EvalCase> cases = evalHarnessRunner.loadCases(projectRoot.resolve(casesFile));
        if (caseIdFilter != null && !caseIdFilter.isBlank()) {
            cases = cases.stream()
                    .filter(c -> c.id().equalsIgnoreCase(caseIdFilter.trim()))
                    .toList();
        }

        String profile = environment.getActiveProfiles().length > 0
                ? String.join(",", environment.getActiveProfiles())
                : String.join(",", environment.getDefaultProfiles());

        EvalReport report = evalHarnessRunner.runSuite(profile, cases, projectRoot);
        Path mdPath = evalHarnessRunner.writeReportFiles(report, projectRoot.resolve(reportDir));

        log.info("\n{}", report.toMarkdown());
        log.info("Eval reports saved to: {}", mdPath.toAbsolutePath());
    }
}
