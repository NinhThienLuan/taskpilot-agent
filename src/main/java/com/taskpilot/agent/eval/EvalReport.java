package com.taskpilot.agent.eval;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record EvalReport(
        String profile,
        Instant generatedAt,
        int totalCases,
        int passedCases,
        double passAt1Rate,
        double avgIterations,
        double avgDurationSec,
        long totalTokens,
        Map<String, String> passRateByDifficulty,
        List<EvalCaseResult> results
) {
    public static EvalReport from(String profile, List<EvalCaseResult> results) {
        int total = results.size();
        int passed = (int) results.stream().filter(EvalCaseResult::passed).count();
        double passRate = total == 0 ? 0.0 : (passed * 100.0) / total;
        double avgIter = results.stream().mapToInt(EvalCaseResult::iterations).average().orElse(0.0);
        double avgSec = results.stream().mapToLong(EvalCaseResult::durationMs).average().orElse(0.0) / 1000.0;
        long tokens = results.stream().mapToLong(r -> r.tokenUsage().totalTokens()).sum();

        Map<String, String> byDiff = results.stream()
                .collect(Collectors.groupingBy(EvalCaseResult::difficulty))
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> {
                            long p = e.getValue().stream().filter(EvalCaseResult::passed).count();
                            return p + "/" + e.getValue().size();
                        }
                ));

        return new EvalReport(profile, Instant.now(), total, passed, passRate, avgIter, avgSec, tokens, byDiff, results);
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# TaskPilot Eval Harness Report\n\n");
        sb.append("- **Profile:** `").append(profile).append("`\n");
        sb.append("- **Generated At:** ").append(generatedAt).append("\n");
        sb.append(String.format("- **Pass@1 Rate:** %.1f%% (%d/%d)\n", passAt1Rate, passedCases, totalCases));
        sb.append(String.format("- **Avg Iterations:** %.2f | **Avg Latency:** %.2fs | **Total Tokens:** %,d\n",
                avgIterations, avgDurationSec, totalTokens));
        sb.append("- **By Difficulty:** ").append(passRateByDifficulty).append("\n\n");

        sb.append("| Case | Difficulty | Result | Failure Reason | Reg | Hygiene | Hidden | Iters | Time (s) | Tokens |\n");
        sb.append("| :--- | :--- | :--- | :--- | :---: | :---: | :---: | ---: | ---: | ---: |\n");
        for (EvalCaseResult r : results) {
            sb.append(String.format("| %s | %s | %s | %s | %s | %s | %s | %d | %.1f | %,d |\n",
                    r.caseId(),
                    r.difficulty(),
                    r.passed() ? "PASS" : "FAIL",
                    r.failureReason(),
                    r.regressionTestsPassed() ? "OK" : "FAIL",
                    r.antiRewardHackingClean() ? "OK" : "FAIL",
                    r.hiddenTestsPassed() ? "OK" : "FAIL",
                    r.iterations(),
                    r.durationMs() / 1000.0,
                    r.tokenUsage().totalTokens()));
        }
        return sb.toString();
    }
}
