package com.mofanyi.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 应用代码生成状态枚举
 */
@Getter
public enum AppGenerateStatusEnum {

    NOT_GENERATED("未生成", "not_generated"),
    GENERATING("生成中", "generating"),
    DONE("已完成", "done"),
    PAUSED("已暂停", "paused"),
    STOPPED("已停止", "stopped"),
    FAILED("失败", "failed");

    private final String text;

    private final String value;

    AppGenerateStatusEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static AppGenerateStatusEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (AppGenerateStatusEnum anEnum : AppGenerateStatusEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
