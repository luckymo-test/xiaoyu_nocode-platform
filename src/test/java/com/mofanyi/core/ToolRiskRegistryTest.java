package com.mofanyi.core;

import com.mofanyi.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具风险分级测试（plan-agent-engineering 第四章）：
 * 出站白名单（防 SSRF）与写文件大小上限。
 */
class ToolRiskRegistryTest {

    private final ToolRiskRegistry registry = new ToolRiskRegistry();

    @Test
    @DisplayName("出站白名单：放行图库来源，拦截其它域名（含内网）")
    void outboundAllowlist() {
        assertDoesNotThrow(() -> registry.checkOutboundUrl("https://images.pexels.com/photos/1/a.jpg"));
        assertDoesNotThrow(() -> registry.checkOutboundUrl("https://cdn.pixabay.com/photo/x.jpg"));
        assertDoesNotThrow(() -> registry.checkOutboundUrl("https://pexels.com/x.jpg"));

        assertThrows(BusinessException.class, () -> registry.checkOutboundUrl("http://127.0.0.1:8080/admin"));
        assertThrows(BusinessException.class, () -> registry.checkOutboundUrl("http://169.254.169.254/latest/meta-data/"));
        assertThrows(BusinessException.class, () -> registry.checkOutboundUrl("http://localhost:6379/"));
        assertThrows(BusinessException.class, () -> registry.checkOutboundUrl("https://evil.com/pexels.com/x.jpg"));
        assertThrows(BusinessException.class, () -> registry.checkOutboundUrl("not a url"));
        assertThrows(BusinessException.class, () -> registry.checkOutboundUrl(null));
    }

    @Test
    @DisplayName("写文件大小上限")
    void writeSizeLimit() {
        assertDoesNotThrow(() -> registry.checkWriteContent("writeFile", "x".repeat(1000)));
        assertDoesNotThrow(() -> registry.checkWriteContent("writeFile", null));
        assertThrows(BusinessException.class,
                () -> registry.checkWriteContent("writeFile", "x".repeat(200_001)));
    }

    @Test
    @DisplayName("风险表覆盖全部工具，且两类高危工具被标注")
    void riskTable() {
        var risks = registry.risks();
        assertFalse(risks.isEmpty());
        assertTrue(risks.stream().anyMatch(r -> "searchImage".equals(r.tool())
                && r.risk() == ToolRiskRegistry.Risk.HIGH));
        assertTrue(risks.stream().anyMatch(r -> "diagram".equals(r.tool())
                && r.risk() == ToolRiskRegistry.Risk.HIGH));
        assertTrue(risks.stream().anyMatch(r -> "readFile".equals(r.tool())
                && r.risk() == ToolRiskRegistry.Risk.LOW));
    }
}
