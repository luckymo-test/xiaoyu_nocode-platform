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

    /**
     * 工程允许声明的直接依赖。模型不能通过 package.json 扩展到该集合之外。
     */
    default Set<String> allowedDependencies() {
        return Set.of();
    }

    /**
     * 允许出现在 package.json build 脚本中的固定命令。
     */
    default Set<String> allowedBuildScripts() {
        return Set.of();
    }
}
