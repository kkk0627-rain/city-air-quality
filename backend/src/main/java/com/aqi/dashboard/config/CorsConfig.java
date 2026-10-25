package com.aqi.dashboard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * 跨域配置：允许 Vite 开发服务器（默认 5173）直连后端 8080。
 *
 * <p>采用 {@code CorsFilter} 而非 {@code WebMvcConfigurer#addCorsMappings}，
 * 原因是前者在过滤器链最前端生效，能覆盖被全局异常处理接管后的错误响应
 * （错误响应若不带 CORS 头，浏览器会把 4xx/5xx 统一报成 CORS 错误，极难排查）。</p>
 *
 * <p>依赖任务 3（工程骨架）。与任务 5/6 无编译依赖，三者可并行开发。</p>
 */
@Configuration
public class CorsConfig {

    /** Vite 默认端口；同时允许 127.0.0.1，避免 localhost 解析到 IPv6 时失配 */
    private static final List<String> ALLOWED_ORIGINS = Arrays.asList(
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:5174",
            "http://127.0.0.1:5174"
    );

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // 不使用 "*"：前端若不传 credentials 时可用 "*"，
        // 但一旦后续接入 Cookie 认证，"*" 会直接报错，故显式列出来源
        config.setAllowedOrigins(ALLOWED_ORIGINS);
        config.setAllowedMethods(Arrays.asList("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(Arrays.asList("Content-Type", "Authorization"));
        // 预检请求结果缓存 1 小时，减少 OPTIONS 往返
        config.setMaxAge(3600L);
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public CorsFilter corsFilter(CorsConfigurationSource corsConfigurationSource) {
        return new CorsFilter(corsConfigurationSource);
    }
}
