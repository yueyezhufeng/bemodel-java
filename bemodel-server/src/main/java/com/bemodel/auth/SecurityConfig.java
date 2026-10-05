package com.bemodel.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 安全策略（演示环境的"真安全"）：无状态 JWT；/api/auth/login 放行；
 * GET /api/** 三+评审员角色皆可；写操作（POST/PUT/DELETE）仅 ADMIN/EDITOR；
 * 例外1：/api/cs/ask、/api/cs/clarify/**、/api/search 为问答语义查询（澄清回答与提问同级），
 * /api/cs/feedback 为评议提交，各角色皆可——这些端点会产生问答伴生状态（澄清任务推进、
 * 缺口提案回流），但只写伴生表，不写建模数据（上方写不变量针对建模数据）；
 * 例外2：概念发布/废弃与本体版本发布是评审动作，REVIEWER 也放行（服务层另有评审门禁：非评审员/管理员会被拒）。
 * 401/403 统一返回 Result 风格 JSON。CORS 策略与 CorsConfig 现状一致。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/api/auth/login").permitAll()
                        // 问一问/搜索是问答语义查询：各角色皆可（虽走 POST，只写澄清任务/缺口提案等问答伴生状态，不写建模数据）
                        .requestMatchers(HttpMethod.POST, "/api/cs/ask", "/api/cs/clarify/**", "/api/search")
                            .hasAnyRole("ADMIN", "EDITOR", "REVIEWER", "VIEWER")
                        // 路由反馈：各角色皆可提交（评议）；反馈列表仅管理角色
                        .requestMatchers(HttpMethod.POST, "/api/cs/feedback")
                            .hasAnyRole("ADMIN", "EDITOR", "REVIEWER", "VIEWER")
                        // 评审动作放 REVIEWER：概念发布/废弃 + 本体版本发布（服务层另有评审门禁兜底）
                        .requestMatchers(HttpMethod.POST, "/api/concept/transition/**", "/api/release/publish")
                            .hasAnyRole("ADMIN", "EDITOR", "REVIEWER")
                        // 知识库写操作（借鉴 5）：上传/编辑/删除限管理角色；发布/审批动作放 REVIEWER
                        //（服务层 transition 另有评审门禁+防自审兜底，对齐概念域）
                        .requestMatchers(HttpMethod.POST, "/api/knowledge/documents/*/transition",
                                "/api/knowledge/entries/*/transition")
                            .hasAnyRole("ADMIN", "EDITOR", "REVIEWER")
                        .requestMatchers(HttpMethod.POST, "/api/knowledge/**")
                            .hasAnyRole("ADMIN", "EDITOR")
                        .requestMatchers(HttpMethod.PUT, "/api/knowledge/**")
                            .hasAnyRole("ADMIN", "EDITOR")
                        .requestMatchers(HttpMethod.DELETE, "/api/knowledge/**")
                            .hasAnyRole("ADMIN", "EDITOR")
                        .requestMatchers(HttpMethod.GET, "/api/cs/feedback/list").hasAnyRole("ADMIN", "EDITOR")
                        // 澄清任务列表是维护者视图（含用户原话证据），不开放 REVIEWER/VIEWER
                        .requestMatchers(HttpMethod.GET, "/api/cs/clarify/list").hasAnyRole("ADMIN", "EDITOR")
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("ADMIN", "EDITOR", "REVIEWER", "VIEWER")
                        .requestMatchers("/api/**").hasAnyRole("ADMIN", "EDITOR")
                        .anyRequest().permitAll())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeJson(res, 401, "未登录或已过期"))
                        .accessDeniedHandler((req, res, ex) -> writeJson(res, 403, "权限不足")));
        return http.build();
    }

    /** 与既有 CorsConfig（WebMvcConfigurer）同一策略：安全链路的预检请求走这里 */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("*"));
        config.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    private static void writeJson(jakarta.servlet.http.HttpServletResponse res, int code, String msg)
            throws IOException {
        res.setStatus(code);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(new ObjectMapper().writeValueAsString(Map.of("code", code, "msg", msg)));
    }
}
