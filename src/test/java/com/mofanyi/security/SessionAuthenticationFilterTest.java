package com.mofanyi.security;

import com.mofanyi.constant.UserConstant;
import com.mofanyi.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionAuthenticationFilterTest {

    private final SessionAuthenticationFilter filter = new SessionAuthenticationFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Session 中的登录用户会映射为 ROLE_ADMIN")
    void mapsAdminSessionToAuthority() throws Exception {
        User user = User.builder().id(7L).userRole(UserConstant.ADMIN_ROLE).build();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(UserConstant.USER_LOGIN_STATE, user);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        assertEquals(user, authentication.getPrincipal());
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority())));
    }

    @Test
    @DisplayName("没有 Session 时不会创建 Session 或伪造登录态")
    void leavesAnonymousRequestUnauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(request.getSession(false));
    }
}
