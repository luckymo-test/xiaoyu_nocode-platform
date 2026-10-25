package com.mofanyi.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationReportServiceTest {

    @Test
    @DisplayName("20 条通过样本生成稳定 JSON/Markdown 并触发质量门禁")
    void aggregatesVersionedBaseline() {
        EvaluationReportService service = new EvaluationReportService(new ObjectMapper());
        List<EvaluationReportService.CaseResult> cases = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            cases.add(new EvaluationReportService.CaseResult(
                    "case-" + index, "vue-vite", "default-neutral",
                    "deepseek-flash", "standard", true, true, 0,
                    1_000, 500, index == 19 ? 9_000 : 5_000,
                    1, 8.0, true, true, true));
        }

        EvaluationReportService.Report report =
                service.aggregate("1", "deepseek-flash", cases);

        assertTrue(report.gatePassed());
        assertEquals(1.0, report.metrics().buildRate());
        assertEquals(8.0, report.metrics().averageVisualScore());
        assertTrue(service.toJson(report).contains("\"gatePassed\" : true"));
        assertTrue(service.toMarkdown(report).contains("| Duration p95 | 5000 ms |"));
    }

    @Test
    @DisplayName("不足 20 条时质量门禁失败")
    void requiresTwentyCaseCatalog() {
        EvaluationReportService service = new EvaluationReportService(new ObjectMapper());
        var one = new EvaluationReportService.CaseResult(
                "one", "next-static-export", "saas-product",
                "deepseek-flash", "standard", true, true, 0,
                10, 20, 100, 0, 5, true, true, true);

        assertFalse(service.aggregate("1", "deepseek-flash", List.of(one)).gatePassed());
    }
}
