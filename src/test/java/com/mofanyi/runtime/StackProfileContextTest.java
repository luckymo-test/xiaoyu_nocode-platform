package com.mofanyi.runtime;

import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StackProfileContextTest {

    @Test
    @DisplayName("Job Profile 优先于默认 Vue，并与生成类型绑定")
    void resolvesActiveProfileAndRejectsWrongType() {
        StackProfileRegistry registry = new StackProfileRegistry(true, false);
        StackProfileContext context = new StackProfileContext(registry);
        StackProfile react = registry.require("react-vite-ts");
        context.activate(7L, react);

        assertEquals("react-vite-ts", context.resolve(7L, CodeGenTypeEnum.VUE).id());
        assertThrows(RuntimeException.class,
                () -> context.resolve(7L, CodeGenTypeEnum.HTML));
        assertEquals("vue-vite", context.resolve(8L, CodeGenTypeEnum.VUE).id());

        context.deactivate(7L, react);
        assertNull(context.get(7L));
    }
}
