package com.mofanyi.runtime;

import java.time.Duration;
import java.util.List;

/**
 * 由 Profile 注册表提供的固定命令。模型只能选择 Profile，不能提交命令参数。
 */
public record CommandSpec(String phase, List<String> executable, Duration timeout) {

    public CommandSpec {
        executable = executable == null ? List.of() : List.copyOf(executable);
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
    }

    public boolean noop() {
        return executable.isEmpty();
    }
}
