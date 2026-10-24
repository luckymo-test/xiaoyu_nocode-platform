package com.mofanyi.runtime;

import com.mofanyi.model.enums.CodeGenTypeEnum;

import java.util.List;
import java.util.Set;

/**
 * 技术栈/运行时的受控描述。Profile 决定构建命令、输出目录和部署形态。
 */
public interface StackProfile {

    String id();

    String version();

    String displayName();

    CodeGenTypeEnum compatibilityType();

    Set<String> capabilities();

    boolean available();

    String unavailableReason();

    List<CommandSpec> installCommands();

    List<CommandSpec> buildCommands();

    String outputDirectory();
}
