package com.mofanyi.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 调用明细：每次模型调用一行，承载高基数维度（用户/应用/内容/错误），供 Grafana 排行与排查。
 * 聚合指标走 Prometheus（见 AiMetricsRecorder），此处只存"谁、用了多少、成功失败"的明细。
 *
 * @author 程序员luckymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("ai_call_log")
public class AiCallLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 应用 id
     */
    @Column("appId")
    private Long appId;

    /**
     * 用户 id
     */
    @Column("userId")
    private Long userId;

    /**
     * 调用类型：html / multi_file / vue / modify / fix
     */
    @Column("callType")
    private String callType;

    /**
     * 模型名称
     */
    @Column("model")
    private String model;

    /**
     * 本次调用的最大输出 token 配置
     */
    @Column("maxOutputTokens")
    private Integer maxOutputTokens;

    /**
     * 输入 token
     */
    @Column("inputTokens")
    private Integer inputTokens;

    /**
     * 输出 token（推理模型的 reasoning 计入其中）
     */
    @Column("outputTokens")
    private Integer outputTokens;

    /**
     * 总 token
     */
    @Column("totalTokens")
    private Integer totalTokens;

    /**
     * 响应时长（毫秒）
     */
    @Column("durationMs")
    private Long durationMs;

    /**
     * 模型停止原因：stop / length / tool_calls 等
     */
    @Column("finishReason")
    private String finishReason;

    /**
     * 调用状态：success / error
     */
    @Column("status")
    private String status;

    /**
     * 失败时的错误信息（原始文本，仅明细/日志）
     */
    @Column("errorMsg")
    private String errorMsg;

    /**
     * 创建时间（请求时间）
     */
    @Column("createTime")
    private LocalDateTime createTime;
}
