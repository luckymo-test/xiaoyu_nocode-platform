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
@Table("agent_step")
public class AgentStep {
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;
    @Column("jobId")
    private Long jobId;
    @Column("planId")
    private Long planId;
    @Column("stepKey")
    private String stepKey;
    @Column("agentType")
    private String agentType;
    private String title;
    private String status;
    @Column("dependenciesJson")
    private String dependenciesJson;
    @Column("inputJson")
    private String inputJson;
    @Column("outputJson")
    private String outputJson;
    @Column("inputArtifactId")
    private Long inputArtifactId;
    @Column("outputArtifactId")
    private Long outputArtifactId;
    private Integer attempt;
    @Column("maxAttempts")
    private Integer maxAttempts;
    @Column("errorType")
    private String errorType;
    @Column("errorMessage")
    private String errorMessage;
    @Column("startedAt")
    private LocalDateTime startedAt;
    @Column("finishedAt")
    private LocalDateTime finishedAt;
    @Column("createTime")
    private LocalDateTime createTime;
    @Column("updateTime")
    private LocalDateTime updateTime;
}
