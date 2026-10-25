# 固定 Prompt 评估基线

> 日期：2026-10-25
> 状态：20 条 Prompt 和报告聚合器完成；真实模型批量运行待执行

## 指标

每个案例记录：

- Prompt、StackProfile、Template、模型和 reasoning level；
- build、render、内容匹配、响应式和交互通过情况；
- Prompt/Completion token、总耗时、修复轮次和视觉评分；
- 图片问题数量。

`EvaluationReportService` 汇总平均值和 P95 耗时，并生成 JSON 与 Markdown。质量门禁要求：

- 至少 20 条案例；
- build、render、响应式通过率 ≥ 95%；
- 内容匹配和交互通过率 ≥ 90%；
- 平均视觉评分 ≥ 7/10。

## 当前边界

报告内核已可对固定集运行结果做稳定聚合和版本比较。真实模型批量运行仍作为显式外部探针，
避免普通测试产生模型费用；下一阶段需要把 20 条真实生成结果接入该聚合器并保存 baseline 文件。
