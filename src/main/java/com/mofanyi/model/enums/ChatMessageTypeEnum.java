package com.mofanyi.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 对话消息类型枚举
 */
@Getter
public enum ChatMessageTypeEnum {

    USER("用户消息", "user"),
    ASSISTANT("AI 回复", "assistant"),
    ERROR("错误信息", "error");

    private final String text;

    private final String value;

    ChatMessageTypeEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static ChatMessageTypeEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (ChatMessageTypeEnum anEnum : ChatMessageTypeEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
