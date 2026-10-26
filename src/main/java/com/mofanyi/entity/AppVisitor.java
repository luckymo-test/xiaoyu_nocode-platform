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
@Table("app_visitor")
public class AppVisitor {
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;
    @Column("workspaceId")
    private Long workspaceId;
    @Column("appId")
    private Long appId;
    @Column("shareId")
    private Long shareId;
    @Column("visitorId")
    private String visitorId;
    @Column("displayName")
    private String displayName;
    @Column("lastSeenAt")
    private LocalDateTime lastSeenAt;
    @Column("createTime")
    private LocalDateTime createTime;
}
