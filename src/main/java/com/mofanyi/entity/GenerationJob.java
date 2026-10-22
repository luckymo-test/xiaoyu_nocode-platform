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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_job")
public class GenerationJob implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("workspaceId")
    private Long workspaceId;

    @Column("appId")
    private Long appId;

    @Column("requestedBy")
    private Long requestedBy;

    @Column("requestType")
    private String requestType;

    @Column("idempotencyKey")
    private String idempotencyKey;

    @Column("requestHash")
    private String requestHash;

    @Column("requestPayload")
    private String requestPayload;

    @Column("userMessageId")
    private Long userMessageId;

    private String status;

    @Column("currentStage")
    private String currentStage;

    @Column("codeGenType")
    private String codeGenType;

    @Column("stackProfileId")
    private String stackProfileId;

    @Column("templateBundleId")
    private String templateBundleId;

    @Column("templateVersion")
    private String templateVersion;

    @Column("templateHash")
    private String templateHash;

    @Column("skillsJson")
    private String skillsJson;

    @Column("baseRevisionId")
    private Long baseRevisionId;

    @Column("candidateRevisionId")
    private Long candidateRevisionId;

    @Column("publishedRevisionId")
    private Long publishedRevisionId;

    private String visibility;
    private Integer priority;
    private Integer attempt;

    @Column("maxAttempts")
    private Integer maxAttempts;

    @Column("leaseOwner")
    private String leaseOwner;

    @Column("leaseExpiresAt")
    private LocalDateTime leaseExpiresAt;

    @Column("fencingToken")
    private Long fencingToken;

    @Column("nextEventSeq")
    private Long nextEventSeq;

    @Column("errorType")
    private String errorType;

    @Column("errorMessage")
    private String errorMessage;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;
}
