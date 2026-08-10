package com.mofanyi.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 应用精选状态枚举
 */
@Getter
public enum AppFeaturedStatusEnum {

    NONE(0, "未申请"),
    PENDING(1, "待审核"),
    APPROVED(2, "已推荐"),
    REJECTED(3, "已拒绝");

    private final int value;

    private final String text;

    AppFeaturedStatusEnum(int value, String text) {
        this.value = value;
        this.text = text;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static AppFeaturedStatusEnum getEnumByValue(Integer value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (AppFeaturedStatusEnum anEnum : AppFeaturedStatusEnum.values()) {
            if (anEnum.value == value) {
                return anEnum;
            }
        }
        return null;
    }
}
