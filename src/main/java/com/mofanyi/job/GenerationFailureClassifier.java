package com.mofanyi.job;

import com.mofanyi.ai.task.GenerationCancelledException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

/**
 * 只重试可恢复的基础设施/模型瞬态错误，参数和版本冲突错误不重试。
 */
@Component
public class GenerationFailureClassifier {

    public boolean retryable(Throwable error) {
        if (error == null || error instanceof GenerationCancelledException) {
            return false;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        while (current != null && visited.add(current)) {
            if (current instanceof GenerationCancelledException) {
                return false;
            }
            String text = (current.getClass().getName() + " "
                    + String.valueOf(current.getMessage())).toLowerCase(Locale.ROOT);
            if (text.contains("timeout")
                    || text.contains("timed out")
                    || text.contains("429")
                    || text.contains("rate limit")
                    || text.contains("overloaded")
                    || text.contains("temporarily")
                    || text.contains("connection reset")
                    || text.contains("connection refused")
                    || text.contains("premature close")
                    || text.contains("rst_stream")
                    || text.contains("stream reset")
                    || text.contains("eof")
                    || text.contains("502")
                    || text.contains("503")
                    || text.contains("504")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
