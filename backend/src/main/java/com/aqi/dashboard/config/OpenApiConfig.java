package com.aqi.dashboard.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / OpenAPI 3 文档配置。
 *
 * <p>为什么单独写一个配置类而不是只靠默认行为：默认生成的文档 title 是
 * "OpenAPI definition"、没有任何描述与接口分组，答辩时看不出项目是做什么的。
 * 这里把标题、版本、数据口径、等级标准都写进文档首页，
 * 打开 Swagger UI 第一眼就能看懂这份接口是查什么的。</p>
 *
 * <p>两个访问入口：</p>
 * <ul>
 *   <li>{@code http://localhost:8080/swagger-ui/index.html} —— 可交互调试页</li>
 *   <li>{@code http://localhost:8080/v3/api-docs} —— OpenAPI 3.0 规范 JSON</li>
 * </ul>
 *
 * <p>注意：接口本身仍受 {@link CorsConfig} 约束，Swagger UI 与后端同源
 * （都是从 8080 发出的），因此调试不受跨域影响。</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aqiDashboardOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("城市空气质量看板 API")
                        .description(""
                                + "面向《城市空气质量监测看板》课程项目的后端接口。\n\n"
                                + "**数据口径**\n"
                                + "- 数据源：中国环境监测总站 2025 年全年日均值，12 个城市 × 361 天 = 4,332 条\n"
                                + "- 日期范围：2025-01-01 ~ 2025-12-31（注意数据集的\"今天\"是 2025-12-31，不是系统当前日期）\n"
                                + "- AQI 与等级均按 HJ 633-2012 计算：AQI = max(六项污染物的 IAQI)\n"
                                + "- 六个等级：优 / 良 / 轻度污染 / 中度污染 / 重度污染 / 严重污染\n\n"
                                + "**统一响应结构**\n"
                                + "```\n{ \"code\": 0, \"msg\": \"ok\", \"data\": ... }\n```\n"
                                + "`code` 为 0 表示成功，非 0 时与 HTTP 状态码一致（400 参数错误 / 404 资源不存在 / 500 服务端异常）。\n\n"
                                + "**指标键（metric）**：`aqi` / `pm25` / `pm10` / `so2` / `no2` / `co` / `o3`")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("城市空气质量看板项目组")
                                .url("http://localhost:8080/swagger-ui/index.html"))
                        .license(new License()
                                .name("课程作业")
                                .url("http://localhost:8080/v3/api-docs")))
                .externalDocs(new ExternalDocumentation()
                        .description("数据集与接口契约说明（仓库内文档）")
                        .url("http://localhost:8080/v3/api-docs"));
    }
}
