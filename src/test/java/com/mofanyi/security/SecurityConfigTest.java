package com.mofanyi.security;

import com.mofanyi.config.CorsConfig;
import com.mofanyi.config.SecurityProperties;
import com.mofanyi.controller.UserController;
import com.mofanyi.controller.SecurityController;
import com.mofanyi.model.vo.LoginUserVO;
import com.mofanyi.service.UserService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UserController.class)
@ContextConfiguration(classes = SecurityConfigTest.TestApplication.class)
@Import({SecurityConfig.class, MethodSecurityConfig.class, CorsConfig.class,
        SecurityProperties.class, SessionAuthenticationFilter.class,
        UserController.class, SecurityController.class})
@TestPropertySource(properties = {
        "codegen.security.spring-security.enabled=true",
        "codegen.security.csrf.enabled=true",
        "codegen.security.cors.allowed-origins[0]=http://localhost:5199",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"
})
class SecurityConfigTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private SqlSessionFactory sqlSessionFactory;

    @MockitoBean
    private SqlSessionTemplate sqlSessionTemplate;

    @Autowired
    private CsrfTokenRepository csrfTokenRepository;

    @Test
    @DisplayName("CSRF Token 使用可被 SPA 读取的 Cookie 仓储")
    void usesCookieCsrfRepository() {
        assertInstanceOf(CookieCsrfTokenRepository.class, csrfTokenRepository);
    }

    @BeforeEach
    void configureMyBatisMocks() {
        Configuration configuration = new Configuration();
        when(sqlSessionFactory.getConfiguration()).thenReturn(configuration);
        when(sqlSessionTemplate.getConfiguration()).thenReturn(configuration);
    }

    @Test
    @DisplayName("未登录访问受保护接口返回统一 401 响应")
    void rejectsAnonymousProtectedRequest() throws Exception {
        mockMvc.perform(get("/app/get/vo").param("id", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("Cookie 写请求缺少 CSRF Token 时返回 403")
    void rejectsPostWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"demo\",\"userPassword\":\"12345678\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("公开 CSRF 引导接口告知前端 CSRF 已启用")
    void exposesCsrfBootstrapStatus() throws Exception {
        mockMvc.perform(get("/v1/security/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
    }

    @Test
    @DisplayName("携带 CSRF Token 的登录请求可进入现有登录逻辑")
    void acceptsLoginWithCsrfToken() throws Exception {
        LoginUserVO loginUser = new LoginUserVO();
        loginUser.setUserAccount("demo");
        when(userService.userLogin(anyString(), anyString(), any()))
                .thenReturn(loginUser);

        mockMvc.perform(post("/user/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"demo\",\"userPassword\":\"12345678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("普通用户不能调用管理员方法级权限接口")
    @WithMockUser(username = "demo", roles = "USER")
    void rejectsNonAdminMethod() throws Exception {
        mockMvc.perform(post("/user/list/page/vo")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("CORS 仅放行配置中的 Origin")
    void appliesCorsAllowlist() throws Exception {
        mockMvc.perform(options("/user/login")
                        .header("Origin", "http://localhost:5199")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin",
                        "http://localhost:5199"));

        mockMvc.perform(options("/user/login")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
