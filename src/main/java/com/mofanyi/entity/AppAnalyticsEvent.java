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
@Table("app_analytics_event")
public class AppAnalyticsEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("appId")
    private Long appId;

    @Column("workspaceId")
    private Long workspaceId;

    @Column("revisionId")
    private Long revisionId;

    @Column("eventName")
    private String eventName;

    @Column("sessionId")
    private String sessionId;

    private String path;
    private String referrer;

    @Column("deviceType")
    private String deviceType;

    private String region;
    private String metadata;

    @Column("eventTime")
    private LocalDateTime eventTime;

    @Column("createTime")
    private LocalDateTime createTime;
}
