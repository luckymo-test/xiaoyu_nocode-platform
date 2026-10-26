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
@Table("agent_plan")
public class AgentPlan {
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;
    @Column("jobId")
    private Long jobId;
    @Column("appId")
    private Long appId;
    @Column("planVersion")
    private Integer planVersion;
    private String status;
    @Column("requirementJson")
    private String requirementJson;
    @Column("architectureJson")
    private String architectureJson;
    @Column("uiJson")
    private String uiJson;
    @Column("executionPlanJson")
    private String executionPlanJson;
    @Column("planHash")
    private String planHash;
    @Column("confirmedBy")
    private Long confirmedBy;
    @Column("confirmedAt")
    private LocalDateTime confirmedAt;
    @Column("createTime")
    private LocalDateTime createTime;
    @Column("updateTime")
    private LocalDateTime updateTime;
}
