package com.mofanyi.controller;

import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.evaluation.EvaluationPrompt;
import com.mofanyi.evaluation.EvaluationPromptCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/evaluation")
public class EvaluationController {

    private final EvaluationPromptCatalog catalog;

    public EvaluationController(EvaluationPromptCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/prompts")
    public BaseResponse<Map<String, Object>> prompts() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", catalog.version());
        result.put("count", catalog.prompts().size());
        result.put("prompts", catalog.prompts());
        return ResultUtils.success(result);
    }
}
