package com.mofanyi.model.enums;

import lombok.Getter;

import java.util.Set;

@Getter
public enum GenerationJobStatusEnum {

    QUEUED("QUEUED"),
    RUNNING("RUNNING"),
    WAITING_CONFIRMATION("WAITING_CONFIRMATION"),
    PAUSE_REQUESTED("PAUSE_REQUESTED"),
    PAUSED("PAUSED"),
    WAITING_RETRY("WAITING_RETRY"),
    CANCEL_REQUESTED("CANCEL_REQUESTED"),
    SUCCEEDED("SUCCEEDED"),
    FAILED("FAILED"),
    CANCELLED("CANCELLED");

    private static final Set<String> ACTIVE = Set.of(
            QUEUED.value, RUNNING.value, WAITING_CONFIRMATION.value,
            PAUSE_REQUESTED.value, PAUSED.value, WAITING_RETRY.value,
            CANCEL_REQUESTED.value);
    private static final Set<String> TERMINAL = Set.of(
            SUCCEEDED.value, FAILED.value, CANCELLED.value);

    private final String value;

    GenerationJobStatusEnum(String value) {
        this.value = value;
    }

    public static boolean isActive(String value) {
        return ACTIVE.contains(value);
    }

    public static boolean isTerminal(String value) {
        return TERMINAL.contains(value);
    }
}
