package com.mofanyi.worker;

import com.mofanyi.job.GenerationExecutionCondition;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Worker 只负责生成；浏览器/npm 执行属于后续 Execution Worker。
 */
@Component
@ConditionalOnProperty(
        name = GenerationExecutionCondition.MODE_PROPERTY,
        havingValue = GenerationExecutionCondition.WORKER)
public class GenerationWorkerConfigurationValidator implements InitializingBean {

    @Value("${codegen.verify.enabled:false}")
    private boolean renderVerifyEnabled;

    @Override
    public void afterPropertiesSet() {
        if (renderVerifyEnabled) {
            throw new IllegalStateException(
                    "Generation Worker 不允许启动浏览器；请关闭 codegen.verify.enabled");
        }
    }
}
