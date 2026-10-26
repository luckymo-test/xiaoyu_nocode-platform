package com.mofanyi.service;

import com.mofanyi.entity.AiCallLog;
import com.mofanyi.entity.App;
import com.mofanyi.entity.User;
import com.mofanyi.mapper.AiCallLogMapper;
import com.mofanyi.mapper.AppMapper;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class UserUsageService {

    public record UsageSummary(
            LocalDate from,
            LocalDate to,
            long generationCount,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            long estimatedCostMicros,
            long storageBytes) {
    }

    private final AiCallLogMapper callLogMapper;
    private final AppMapper appMapper;
    private final CodeFileWriteService codeFiles;

    @Value("${codegen.usage.input-cost-micros-per-million:140000}")
    private long inputCostMicros;

    @Value("${codegen.usage.output-cost-micros-per-million:280000}")
    private long outputCostMicros;

    public UserUsageService(AiCallLogMapper callLogMapper, AppMapper appMapper,
                            CodeFileWriteService codeFiles) {
        this.callLogMapper = callLogMapper;
        this.appMapper = appMapper;
        this.codeFiles = codeFiles;
    }

    public UsageSummary summary(User user, LocalDate from, LocalDate to) {
        ThrowUtils.throwIf(user == null || user.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        ThrowUtils.throwIf(start.isAfter(end), ErrorCode.PARAMS_ERROR, "开始日期不能晚于结束日期");
        List<AiCallLog> logs = callLogMapper.selectListByQuery(QueryWrapper.create()
                .eq("userId", user.getId())
                .ge("createTime", start.atStartOfDay())
                .lt("createTime", end.plusDays(1).atStartOfDay()));
        long prompt = 0;
        long completion = 0;
        long generation = 0;
        for (AiCallLog log : logs) {
            prompt += value(log.getInputTokens());
            completion += value(log.getOutputTokens());
            if ("GENERATE".equalsIgnoreCase(log.getModelTask())) {
                generation++;
            }
        }
        long cost = Math.round(prompt * (double) inputCostMicros / 1_000_000d)
                + Math.round(completion * (double) outputCostMicros / 1_000_000d);
        long storage = storageBytes(user.getId());
        return new UsageSummary(start, end, generation, prompt, completion,
                prompt + completion, cost, storage);
    }

    private long storageBytes(Long userId) {
        long total = 0;
        for (App app : appMapper.selectListByQuery(QueryWrapper.create()
                .eq("userId", userId)
                .eq("isDelete", 0))) {
            try {
                CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
                if (type == null) {
                    continue;
                }
                Path appRoot = Path.of(codeFiles.getTmpRoot(), "apps",
                        String.valueOf(app.getId()));
                if (!Files.isDirectory(appRoot)) {
                    continue;
                }
                try (var paths = Files.walk(appRoot)) {
                    total += paths.filter(Files::isRegularFile)
                            .filter(path -> !path.toString().contains("node_modules"))
                            .filter(path -> !path.toString().contains("\\dist\\"))
                            .mapToLong(path -> {
                                try {
                                    return Files.size(path);
                                } catch (Exception ignored) {
                                    return 0;
                                }
                            }).sum();
                }
            } catch (Exception ignored) {
                // Storage reporting must not fail the usage request.
            }
        }
        return total;
    }

    private long value(Integer value) {
        return value == null ? 0 : value;
    }
}
