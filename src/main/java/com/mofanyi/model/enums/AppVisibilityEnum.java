package com.mofanyi.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 应用可见范围枚举
 */
@Getter
public enum AppVisibilityEnum {

    PUBLIC("公开", "public"),
    PRIVATE("私有", "private");

    private final String text;

    private final String value;

    AppVisibilityEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static AppVisibilityEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (AppVisibilityEnum anEnum : AppVisibilityEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
