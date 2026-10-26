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

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("agent_tool_call")
public class AgentToolCall {
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;
    @Column("jobId")
    private Long jobId;
    @Column("stepId")
    private Long stepId;
    @Column("toolCallId")
    private String toolCallId;
    @Column("toolName")
    private String toolName;
    @Column("argumentsJson")
    private String argumentsJson;
    @Column("resultSummary")
    private String resultSummary;
    private String status;
    @Column("durationMs")
    private Long durationMs;
    @Column("filePath")
    private String filePath;
    @Column("beforeSha256")
    private String beforeSha256;
    @Column("afterSha256")
    private String afterSha256;
    @Column("errorMessage")
    private String errorMessage;
    @Column("createTime")
    private LocalDateTime createTime;
    @Column("updateTime")
    private LocalDateTime updateTime;
}
