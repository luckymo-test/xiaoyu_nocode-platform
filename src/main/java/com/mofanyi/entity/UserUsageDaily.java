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

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_usage_daily")
public class UserUsageDaily {
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;
    @Column("userId")
    private Long userId;
    @Column("statDate")
    private LocalDate statDate;
    @Column("generationCount")
    private Long generationCount;
    @Column("promptTokens")
    private Long promptTokens;
    @Column("completionTokens")
    private Long completionTokens;
    @Column("estimatedCostMicros")
    private Long estimatedCostMicros;
    @Column("storageBytes")
    private Long storageBytes;
    @Column("createTime")
    private LocalDateTime createTime;
    @Column("updateTime")
    private LocalDateTime updateTime;
}
