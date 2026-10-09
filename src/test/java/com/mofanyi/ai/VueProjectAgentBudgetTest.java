package com.mofanyi.ai;

import com.mofanyi.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 循环预算测试（plan-agent-engineering 第五章）：超时 / 超 token 要终止，且提示语区分"超预算"。
 */
class VueProjectAgentBudgetTest {

    @Test
    @DisplayName("超出墙钟预算 -> 抛业务异常（提示含「时限」）")
    void exceedsWallClock() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> VueProjectAgent.checkBudget(System.currentTimeMillis() - 700_000L, 0, 0));
        assertTrue(e.getMessage().contains("时限"), "错误信息应说明是超时限：" + e.getMessage());
    }

    @Test
    @DisplayName("超出 token 预算 -> 抛业务异常（提示含「token 预算」）")
    void exceedsTokenBudget() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> VueProjectAgent.checkBudget(System.currentTimeMillis(), 150_000L, 60_000L));
        assertTrue(e.getMessage().contains("token"), "错误信息应说明是超 token：" + e.getMessage());
    }

    @Test
    @DisplayName("预算内放行")
    void withinBudget() {
        assertDoesNotThrow(() -> VueProjectAgent.checkBudget(System.currentTimeMillis(), 1_000L, 2_000L));
    }
}
