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

/** JWT authentication and industrial metadata role boundaries. */
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
                        .requestMatchers(HttpMethod.POST, "/api/search")
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
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("ADMIN", "EDITOR", "REVIEWER", "VIEWER")
                        .requestMatchers("/api/**").hasAnyRole("ADMIN", "EDITOR")
                        .anyRequest().permitAll())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeJson(res, 401, "未登录或已过期"))
                        .accessDeniedHandler((req, res, ex) -> writeJson(res, 403, "权限不足")));
        http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
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
