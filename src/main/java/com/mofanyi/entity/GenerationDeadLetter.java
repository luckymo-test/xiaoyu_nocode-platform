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
@Table("generation_dead_letter")
public class GenerationDeadLetter implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("jobId")
    private Long jobId;

    @Column("workspaceId")
    private Long workspaceId;

    @Column("appId")
    private Long appId;

    @Column("requestedBy")
    private Long requestedBy;

    private Integer attempt;

    @Column("errorType")
    private String errorType;

    @Column("errorMessage")
    private String errorMessage;

    private String payload;
    private String status;

    @Column("replayedBy")
    private Long replayedBy;

    @Column("replayedAt")
    private LocalDateTime replayedAt;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;
}
