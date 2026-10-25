package com.aqi.dashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 城市空气质量看板 - 后端启动类。
 *
 * <p>任务 3（后端工程骨架）的组成部分：pom.xml + application.yml 决定依赖与配置，
 * 本类是 Spring Boot 的入口，两者共同构成"能启动"的最小工程。</p>
 */
@SpringBootApplication
public class AqiDashboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(AqiDashboardApplication.class, args);
    }
}
