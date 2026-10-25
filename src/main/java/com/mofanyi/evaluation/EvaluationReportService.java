package com.mofanyi.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 固定 Prompt 集的基线聚合器。单次生成结果由外部 Evaluation Runner 提供，
 * 本类负责稳定指标、质量门禁和可比较报告，避免用单张主观截图下结论。
 */
public final class EvaluationReportService {

    private final ObjectMapper objectMapper;

    public EvaluationReportService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Report aggregate(String catalogVersion, String model, List<CaseResult> cases) {
        List<CaseResult> values = cases == null ? List.of() : List.copyOf(cases);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("评估结果不能为空");
        }
        int size = values.size();
        long totalPrompt = values.stream().mapToLong(CaseResult::promptTokens).sum();
        long totalCompletion = values.stream().mapToLong(CaseResult::completionTokens).sum();
        List<Long> sortedDuration = values.stream()
                .map(CaseResult::durationMs).sorted().toList();
        long p95Duration = sortedDuration.get(Math.max(0,
                (int) Math.ceil(sortedDuration.size() * 0.95) - 1));
        Metrics metrics = new Metrics(
                size,
                ratio(values, CaseResult::buildSuccess),
                ratio(values, CaseResult::renderPassed),
                ratio(values, CaseResult::contentMatched),
                ratio(values, CaseResult::responsivePassed),
                ratio(values, CaseResult::interactivePassed),
                average(values, CaseResult::visualScore),
                averageLong(values, CaseResult::promptTokens),
                averageLong(values, CaseResult::completionTokens),
                averageLong(values, CaseResult::durationMs),
                p95Duration,
                average(values, CaseResult::fixRounds));
        boolean gatePassed = size >= 20
                && metrics.buildRate() >= 0.95
                && metrics.renderRate() >= 0.95
                && metrics.contentMatchRate() >= 0.90
                && metrics.responsiveRate() >= 0.95
                && metrics.interactiveRate() >= 0.90
                && metrics.averageVisualScore() >= 7.0;
        return new Report(Instant.now().toString(), catalogVersion, model,
                metrics, gatePassed, values);
    }

    public String toJson(Report report) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (Exception e) {
            throw new IllegalStateException("序列化评估报告失败", e);
        }
    }

    public String toMarkdown(Report report) {
        Metrics m = report.metrics();
        StringBuilder out = new StringBuilder();
        out.append("# Evaluation Baseline\n\n")
                .append("- Catalog: `").append(report.catalogVersion()).append("`\n")
                .append("- Model: `").append(report.model()).append("`\n")
                .append("- Generated at: ").append(report.generatedAt()).append("\n")
                .append("- Gate: **").append(report.gatePassed() ? "PASS" : "FAIL").append("**\n\n")
                .append("| Metric | Value |\n|---|---:|\n")
                .append("| Cases | ").append(m.caseCount()).append(" |\n")
                .append("| Build pass | ").append(percent(m.buildRate())).append(" |\n")
                .append("| Render pass | ").append(percent(m.renderRate())).append(" |\n")
                .append("| Content match | ").append(percent(m.contentMatchRate())).append(" |\n")
                .append("| Responsive | ").append(percent(m.responsiveRate())).append(" |\n")
                .append("| Interactive | ").append(percent(m.interactiveRate())).append(" |\n")
                .append("| Visual score | ")
                .append(String.format(Locale.ROOT, "%.2f/10", m.averageVisualScore())).append(" |\n")
                .append("| Prompt tokens avg | ").append(m.averagePromptTokens()).append(" |\n")
                .append("| Completion tokens avg | ").append(m.averageCompletionTokens()).append(" |\n")
                .append("| Duration avg | ").append(m.averageDurationMs()).append(" ms |\n")
                .append("| Duration p95 | ").append(m.p95DurationMs()).append(" ms |\n")
                .append("| Fix rounds avg | ")
                .append(String.format(Locale.ROOT, "%.2f", m.averageFixRounds())).append(" |\n\n")
                .append("## Cases\n\n")
                .append("| Prompt | Profile | Build | Render | Visual | Tokens | Fix |\n")
                .append("|---|---|---:|---:|---:|---:|---:|\n");
        report.cases().stream()
                .sorted(Comparator.comparing(CaseResult::promptId))
                .forEach(item -> out.append("| ").append(item.promptId())
                        .append(" | ").append(item.profileId())
                        .append(" | ").append(item.buildSuccess() ? "PASS" : "FAIL")
                        .append(" | ").append(item.renderPassed() ? "PASS" : "FAIL")
                        .append(" | ").append(item.visualScore())
                        .append(" | ").append(item.promptTokens() + item.completionTokens())
                        .append(" | ").append(item.fixRounds()).append(" |\n"));
        return out.toString();
    }

    private static double ratio(List<CaseResult> values,
                                java.util.function.Predicate<CaseResult> predicate) {
        return values.stream().filter(predicate).count() / (double) values.size();
    }

    private static double average(List<CaseResult> values,
                                  java.util.function.ToDoubleFunction<CaseResult> mapper) {
        return values.stream().mapToDouble(mapper).average().orElse(0);
    }

    private static long averageLong(List<CaseResult> values,
                                    java.util.function.ToLongFunction<CaseResult> mapper) {
        return Math.round(values.stream().mapToLong(mapper).average().orElse(0));
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    public record CaseResult(
            String promptId,
            String profileId,
            String templateId,
            String model,
            String reasoningLevel,
            boolean buildSuccess,
            boolean renderPassed,
            int imageIssueCount,
            long promptTokens,
            long completionTokens,
            long durationMs,
            int fixRounds,
            double visualScore,
            boolean contentMatched,
            boolean responsivePassed,
            boolean interactivePassed) {
    }

    public record Metrics(
            int caseCount,
            double buildRate,
            double renderRate,
            double contentMatchRate,
            double responsiveRate,
            double interactiveRate,
            double averageVisualScore,
            long averagePromptTokens,
            long averageCompletionTokens,
            long averageDurationMs,
            long p95DurationMs,
            double averageFixRounds) {
    }

    public record Report(
            String generatedAt,
            String catalogVersion,
            String model,
            Metrics metrics,
            boolean gatePassed,
            List<CaseResult> cases) {
    }
}
