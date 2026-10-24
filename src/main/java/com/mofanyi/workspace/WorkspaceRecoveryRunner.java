package com.mofanyi.workspace;

import cn.hutool.core.io.FileUtil;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.job.GenerationExecutionCondition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 修复发布过程中崩溃留下的 live/stage/backup 目录。
 */
@Slf4j
@Component
@Conditional(GenerationExecutionCondition.class)
public class WorkspaceRecoveryRunner implements ApplicationRunner {

    private final CodeFileWriteService codeFileWriteService;

    public WorkspaceRecoveryRunner(CodeFileWriteService codeFileWriteService) {
        this.codeFileWriteService = codeFileWriteService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path tmpRoot = Path.of(codeFileWriteService.getTmpRoot()).toAbsolutePath().normalize();
        if (!Files.isDirectory(tmpRoot)) {
            return;
        }
        long staleBefore = System.currentTimeMillis() - 5 * 60_000L;
        File[] backups = tmpRoot.toFile().listFiles((dir, name) -> name.contains(".backup-"));
        if (backups == null) {
            return;
        }
        for (File backup : backups) {
            if (backup.lastModified() > staleBefore) {
                continue;
            }
            String name = backup.getName();
            int marker = name.indexOf(".backup-");
            if (marker <= 0) {
                continue;
            }
            Path live = tmpRoot.resolve(name.substring(0, marker));
            try {
                if (Files.exists(live)) {
                    FileUtil.del(backup.toPath());
                } else {
                    Files.move(backup.toPath(), live);
                    log.warn("[workspace] live 缺失，已从备份恢复：{}", live);
                }
            } catch (IOException e) {
                log.error("[workspace] 恢复备份目录失败，backup={}", backup, e);
            }
        }
        File[] stages = tmpRoot.toFile().listFiles((dir, name) -> name.contains(".publish-"));
        if (stages == null) {
            return;
        }
        for (File stage : stages) {
            if (stage.lastModified() > staleBefore) {
                continue;
            }
            FileUtil.del(stage.toPath());
        }
    }
}
