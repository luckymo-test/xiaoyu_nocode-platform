package com.mofanyi.workflow;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.ai.model.ModelGateway;
import com.mofanyi.ai.model.ModelRequest;
import com.mofanyi.ai.model.ModelResponse;
import com.mofanyi.ai.model.ModelTask;
import com.mofanyi.core.AiCallContext;
import com.mofanyi.entity.AgentPlan;
import com.mofanyi.entity.AgentStep;
import com.mofanyi.entity.GenerationJob;
import com.mofanyi.mapper.AgentPlanMapper;
import com.mofanyi.mapper.AgentStepMapper;
import com.mofanyi.mapper.AgentToolCallMapper;
import com.mofanyi.entity.AgentToolCall;
import com.mofanyi.model.dto.job.GenerationJobCreateRequest;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.template.ProjectTemplateDefinition;
import com.mofanyi.template.ProjectTemplateRegistry;
import com.mofanyi.ai.memory.AppSpecService;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * Produces the pre-confirmation requirement/architecture/UI proposal and the
 * post-confirmation Planner DAG. Structured outputs are persisted before any
 * source workspace is created.
 */
@Service
public class WorkflowPlanService {

    private static final int MAX_PLAN_CHARS = 100_000;

    private final ModelGateway modelGateway;
    private final ObjectMapper objectMapper;
    private final AgentPlanMapper planMapper;
    private final AgentStepMapper stepMapper;
    private final ProjectTemplateRegistry projectTemplates;
    private final AgentToolCallMapper toolCallMapper;
    private final AppSpecService appSpecs;
    private final String requirementPrompt;
    private final String architectPrompt;
    private final String uiPrompt;
    private final String plannerPrompt;

    public WorkflowPlanService(ModelGateway modelGateway,
                               ObjectMapper objectMapper,
                               AgentPlanMapper planMapper,
                               AgentStepMapper stepMapper,
                               ProjectTemplateRegistry projectTemplates,
                               AgentToolCallMapper toolCallMapper,
                               AppSpecService appSpecs) {
        this.modelGateway = modelGateway;
        this.objectMapper = objectMapper;
        this.planMapper = planMapper;
        this.stepMapper = stepMapper;
        this.projectTemplates = projectTemplates;
        this.toolCallMapper = toolCallMapper;
        this.appSpecs = appSpecs;
        this.requirementPrompt = load("prompt/workflow_requirement_prompt.txt");
        this.architectPrompt = load("prompt/workflow_architect_prompt.txt");
        this.uiPrompt = load("prompt/workflow_ui_prompt.txt");
        this.plannerPrompt = load("prompt/workflow_planner_prompt.txt");
    }

    public AgentPlan ensureProposal(GenerationJob job,
                                    CodeGenTypeEnum type,
                                    GenerationJobCreateRequest request,
                                    AiCallContext context) {
        AgentPlan existing = findLatest(job.getId());
        if (existing != null && ("PROPOSED".equals(existing.getStatus())
                || "CONFIRMED".equals(existing.getStatus()))) {
            return existing;
        }

        String base = "生成类型：" + type.getValue()
                + "\n技术栈：" + request.getStackProfileId()
                + "\n视觉模板：" + request.getTemplateBundleId()
                + "\n项目模板：" + StrUtil.blankToDefault(request.getProjectTemplateId(), "无")
                + "\n【应用现状】\n"
                + StrUtil.blankToDefault(appSpecs.buildSpecSection(type, job.getAppId()), "无")
                + "\n用户需求：\n" + request.getMessage();
        JsonNode requirement = call(ModelTask.REQUIREMENT, requirementPrompt,
                base, context, 1_800);
        JsonNode architecture;
        if (StrUtil.isNotBlank(request.getProjectTemplateId())) {
            ProjectTemplateDefinition template =
                    projectTemplates.require(request.getProjectTemplateId());
            architecture = template.schema();
        } else {
            architecture = call(ModelTask.ARCHITECT, architectPrompt,
                    base + "\n【需求分析】\n" + requirement, context, 2_600);
        }
        JsonNode ui = call(ModelTask.UI, uiPrompt,
                base + "\n【需求分析】\n" + requirement + "\n【架构】\n" + architecture,
                context, 2_600);
        String hash = hash(requirement + "\n" + architecture + "\n" + ui);

        LocalDateTime now = LocalDateTime.now();
        AgentPlan plan = AgentPlan.builder()
                .jobId(job.getId())
                .appId(job.getAppId())
                .planVersion((int) planMapper.selectCountByQuery(
                        QueryWrapper.create().eq("jobId", job.getId())) + 1)
                .status("PROPOSED")
                .requirementJson(requirement.toString())
                .architectureJson(architecture.toString())
                .uiJson(ui.toString())
                .planHash(hash)
                .createTime(now)
                .updateTime(now)
                .build();
        planMapper.insertSelective(plan);
        recordCompletedStep(job.getId(), plan.getId(), "requirement",
                "REQUIREMENT", "需求分析", requirement);
        recordCompletedStep(job.getId(), plan.getId(), "architect",
                "ARCHITECT", "架构与数据权限设计", architecture);
        recordCompletedStep(job.getId(), plan.getId(), "ui",
                "UI", "页面与视觉规划", ui);
        return plan;
    }

    public AgentPlan confirm(GenerationJob job, String planHash, AiCallContext context) {
        AgentPlan plan = findLatest(job.getId());
        if (plan == null || !plan.getPlanHash().equals(planHash)) {
            throw new IllegalStateException("确认的计划版本已变化，请刷新后重新确认");
        }
        if (!"CONFIRMED".equals(plan.getStatus())) {
            JsonNode execution = call(ModelTask.PLAN, plannerPrompt,
                    "【需求】\n" + plan.getRequirementJson()
                            + "\n【架构】\n" + plan.getArchitectureJson()
                            + "\n【UI】\n" + plan.getUiJson(),
                    context, 3_000);
            try {
                validateExecutionPlan(execution);
            } catch (IllegalStateException invalidPlan) {
                execution = fallbackExecutionPlan();
            }
            plan.setExecutionPlanJson(execution.toString());
            plan.setStatus("CONFIRMED");
            plan.setConfirmedAt(LocalDateTime.now());
            plan.setUpdateTime(LocalDateTime.now());
            planMapper.update(plan);
            createExecutionSteps(job.getId(), plan.getId(), execution);
        }
        return plan;
    }

    public AgentPlan revise(GenerationJob job, String planHash, String comment,
                            GenerationJobCreateRequest request, AiCallContext context) {
        AgentPlan current = findLatest(job.getId());
        ThrowUtils.throwIf(current == null || !current.getPlanHash().equals(planHash),
                ErrorCode.OPERATION_ERROR, "确认的计划版本已变化，请刷新后重新操作");
        ThrowUtils.throwIf(StrUtil.isBlank(comment), ErrorCode.PARAMS_ERROR,
                "补充要求不能为空");
        current.setStatus("SUPERSEDED");
        current.setUpdateTime(LocalDateTime.now());
        planMapper.update(current);
        String base = "【原需求】\n" + current.getRequirementJson()
                + "\n【用户补充要求】\n" + comment
                + "\n【原架构】\n" + current.getArchitectureJson();
        JsonNode requirement = call(ModelTask.REQUIREMENT, requirementPrompt,
                base, context, 1_800);
        JsonNode architecture;
        if (StrUtil.isNotBlank(request.getProjectTemplateId())) {
            architecture = projectTemplates.require(request.getProjectTemplateId()).schema();
        } else {
            architecture = call(ModelTask.ARCHITECT, architectPrompt,
                    base + "\n【更新后的需求】\n" + requirement, context, 2_600);
        }
        JsonNode ui = call(ModelTask.UI, uiPrompt,
                base + "\n【更新后的需求】\n" + requirement
                        + "\n【更新后的架构】\n" + architecture,
                context, 2_600);
        String hash = hash(requirement + "\n" + architecture + "\n" + ui);
        AgentPlan revised = AgentPlan.builder()
                .jobId(job.getId())
                .appId(job.getAppId())
                .planVersion(current.getPlanVersion() + 1)
                .status("PROPOSED")
                .requirementJson(requirement.toString())
                .architectureJson(architecture.toString())
                .uiJson(ui.toString())
                .planHash(hash)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        planMapper.insertSelective(revised);
        return revised;
    }

    public AgentPlan findLatest(Long jobId) {
        return planMapper.selectOneByQuery(QueryWrapper.create()
                .eq("jobId", jobId)
                .orderBy("planVersion", false)
                .limit(1));
    }

    public List<AgentStep> listSteps(Long jobId) {
        return stepMapper.selectListByQuery(QueryWrapper.create()
                .eq("jobId", jobId)
                .orderBy("createTime", true));
    }

    public List<AgentToolCall> listToolCalls(Long jobId) {
        return toolCallMapper.selectListByQuery(QueryWrapper.create()
                .eq("jobId", jobId)
                .orderBy("createTime", true));
    }

    public void startFirstPending(Long jobId, String agentType) {
        AgentStep step = firstStep(jobId, agentType, java.util.Set.of("PENDING", "FAILED", "PAUSED"));
        if (step != null) {
            step.setStatus("RUNNING");
            step.setAttempt((step.getAttempt() == null ? 0 : step.getAttempt()) + 1);
            step.setStartedAt(LocalDateTime.now());
            step.setUpdateTime(LocalDateTime.now());
            stepMapper.update(step);
        }
    }

    public void completeAll(Long jobId, String agentType, String outputJson) {
        LocalDateTime now = LocalDateTime.now();
        for (AgentStep step : stepMapper.selectListByQuery(QueryWrapper.create()
                .eq("jobId", jobId)
                .eq("agentType", agentType)
                .in("status", "PENDING", "RUNNING", "FAILED", "PAUSED"))) {
            step.setStatus("SUCCEEDED");
            step.setOutputJson(outputJson);
            step.setFinishedAt(now);
            step.setUpdateTime(now);
            step.setErrorMessage(null);
            step.setErrorType(null);
            stepMapper.update(step);
        }
    }

    public void failRunning(Long jobId, String errorType, String errorMessage, boolean paused) {
        LocalDateTime now = LocalDateTime.now();
        for (AgentStep step : stepMapper.selectListByQuery(QueryWrapper.create()
                .eq("jobId", jobId)
                .eq("status", "RUNNING"))) {
            step.setStatus(paused ? "PAUSED" : "FAILED");
            step.setErrorType(errorType);
            step.setErrorMessage(errorMessage);
            step.setFinishedAt(now);
            step.setUpdateTime(now);
            stepMapper.update(step);
        }
    }

    public void resetStep(Long jobId, String stepKey) {
        AgentStep step = stepMapper.selectOneByQuery(QueryWrapper.create()
                .eq("jobId", jobId)
                .eq("stepKey", stepKey)
                .limit(1));
        ThrowUtils.throwIf(step == null, ErrorCode.NOT_FOUND_ERROR, "执行步骤不存在");
        ThrowUtils.throwIf(!"FAILED".equals(step.getStatus()) && !"PAUSED".equals(step.getStatus()),
                ErrorCode.OPERATION_ERROR, "只有失败或暂停步骤可以重试");
        step.setStatus("PENDING");
        step.setErrorType(null);
        step.setErrorMessage(null);
        step.setFinishedAt(null);
        step.setUpdateTime(LocalDateTime.now());
        stepMapper.update(step);
    }

    private AgentStep firstStep(Long jobId, String agentType, java.util.Set<String> statuses) {
        return stepMapper.selectOneByQuery(QueryWrapper.create()
                .eq("jobId", jobId)
                .eq("agentType", agentType)
                .in("status", statuses)
                .orderBy("createTime", true)
                .limit(1));
    }

    private void createExecutionSteps(Long jobId, Long planId, JsonNode execution) {
        LocalDateTime now = LocalDateTime.now();
        for (JsonNode task : execution.path("tasks")) {
            String key = task.path("key").asText();
            if (StrUtil.isBlank(key)
                    || stepMapper.selectCountByQuery(QueryWrapper.create()
                    .eq("jobId", jobId).eq("stepKey", key)) > 0) {
                continue;
            }
            stepMapper.insertSelective(AgentStep.builder()
                    .jobId(jobId)
                    .planId(planId)
                    .stepKey(key)
                    .agentType(task.path("agentType").asText())
                    .title(task.path("title").asText(key))
                    .status("PENDING")
                    .dependenciesJson(task.path("dependencies").toString())
                    .inputJson(task.path("acceptanceCriteria").toString())
                    .attempt(0)
                    .maxAttempts(2)
                    .createTime(now)
                    .updateTime(now)
                    .build());
        }
    }

    private void recordCompletedStep(Long jobId, Long planId, String key,
                                     String agentType, String title, JsonNode output) {
        LocalDateTime now = LocalDateTime.now();
        stepMapper.insertSelective(AgentStep.builder()
                .jobId(jobId)
                .planId(planId)
                .stepKey(key)
                .agentType(agentType)
                .title(title)
                .status("SUCCEEDED")
                .outputJson(output.toString())
                .attempt(1)
                .maxAttempts(1)
                .startedAt(now)
                .finishedAt(now)
                .createTime(now)
                .updateTime(now)
                .build());
    }

    private JsonNode call(ModelTask task, String systemPrompt, String input,
                          AiCallContext context, int maxTokens) {
        ModelResponse response = modelGateway.chat(new ModelRequest(
                task,
                List.of(new SystemMessage(systemPrompt), new UserMessage(input)),
                List.of(),
                java.util.Set.of(),
                0.1,
                maxTokens,
                false,
                null,
                false,
                null,
                context));
        return normalizeJson(response.text());
    }

    private JsonNode normalizeJson(String raw) {
        String value = StrUtil.blankToDefault(raw, "").trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "");
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start || end - start > MAX_PLAN_CHARS) {
            throw new IllegalStateException("Agent 未返回有效结构化计划");
        }
        try {
            JsonNode node = objectMapper.readTree(value.substring(start, end + 1));
            if (!node.isObject()) {
                throw new IllegalStateException("Agent 计划必须是 JSON 对象");
            }
            return node;
        } catch (Exception e) {
            throw new IllegalStateException("Agent 计划 JSON 解析失败", e);
        }
    }

    private void validateExecutionPlan(JsonNode execution) {
        JsonNode tasks = execution.path("tasks");
        if (!tasks.isArray() || tasks.size() != 3) {
            throw new IllegalStateException("Planner 未生成任务 DAG");
        }
        String[] keys = {"code-project", "review-project", "deploy-project"};
        String[] agents = {"CODER", "REVIEWER", "DEPLOY"};
        for (int i = 0; i < keys.length; i++) {
            JsonNode task = tasks.get(i);
            if (!keys[i].equals(task.path("key").asText())
                    || !agents[i].equals(task.path("agentType").asText())) {
                throw new IllegalStateException("Planner DAG 与执行器契约不一致");
            }
            if (i > 0) {
                JsonNode dependencies = task.path("dependencies");
                if (!dependencies.isArray() || dependencies.size() != 1
                        || !keys[i - 1].equals(dependencies.get(0).asText())) {
                    throw new IllegalStateException("Planner DAG 依赖链不完整");
                }
            }
        }
    }

    private JsonNode fallbackExecutionPlan() {
        var root = objectMapper.createObjectNode();
        var tasks = root.putArray("tasks");
        tasks.add(executionTask("code-project", "按已确认方案生成项目源码", "CODER",
                List.of(), List.of("源码写入隔离工作区", "文件引用完整"),
                List.of("editFile", "writeFile", "writeFileChunk", "readFile", "finish")));
        tasks.add(executionTask("review-project", "执行构建、渲染与安全检查", "REVIEWER",
                List.of("code-project"), List.of("构建成功", "三档视口无阻断问题"),
                List.of("buildProject", "testProject")));
        tasks.add(executionTask("deploy-project", "建立不可变版本与发布产物", "DEPLOY",
                List.of("review-project"), List.of("Revision 固化", "发布目录原子切换"),
                List.of("createPreview", "publishRevision")));
        return root;
    }

    private JsonNode executionTask(String key, String title, String agent,
                                   List<String> dependencies,
                                   List<String> acceptance,
                                   List<String> allowedTools) {
        var task = objectMapper.createObjectNode();
        task.put("key", key);
        task.put("title", title);
        task.put("agentType", agent);
        task.set("dependencies", objectMapper.valueToTree(dependencies));
        task.set("acceptanceCriteria", objectMapper.valueToTree(acceptance));
        task.set("allowedTools", objectMapper.valueToTree(allowedTools));
        return task;
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算计划摘要失败", e);
        }
    }

    private static String load(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("加载工作流 Prompt 失败：" + path, e);
        }
    }
}
