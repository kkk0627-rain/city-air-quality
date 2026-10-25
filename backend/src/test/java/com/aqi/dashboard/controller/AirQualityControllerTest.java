package com.aqi.dashboard.controller;

import com.aqi.dashboard.common.GlobalExceptionHandler;
import com.aqi.dashboard.service.AirQualityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 12 验证：用 MockMvc 独立装配 Controller + 全局异常处理，
 * Service 用 Mockito 打桩，因此<b>不连数据库、不启动容器</b>即可验证接口契约。
 *
 * <p>日期字段的序列化格式不在本测试中断言（standalone MockMvc 不加载 Boot 的 Jackson 配置），
 * 真实格式由上线后的 HTTP 请求验证，见 {@code docs/} 中的任务 12 验收记录。</p>
 */
@DisplayName("任务12 - AirQualityController 接口契约")
class AirQualityControllerTest {

    private AirQualityService mockService;
    private MockMvc mockMvc;

    private static final LocalDate DAY = LocalDate.of(2025, 12, 31);

    @BeforeEach
    void setUp() {
        mockService = Mockito.mock(AirQualityService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AirQualityController(mockService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("C1 GET /api/cities 返回 code=0 与城市数组")
    void cities() throws Exception {
        Mockito.when(mockService.getCities()).thenReturn(List.of(
                new AirQualityService.CityBriefVO(1, "北京", "北京市", "华北")));

        mockMvc.perform(get("/api/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("ok"))
                .andExpect(jsonPath("$.data[0].name").value("北京"))
                .andExpect(jsonPath("$.data[0].region").value("华北"));
    }

    @Test
    @DisplayName("C2 GET /api/aqi/overview 返回概览卡字段")
    void overview() throws Exception {
        Mockito.when(mockService.getOverview(1))
                .thenReturn(new AirQualityService.OverviewVO("北京", DAY, 19, 1, "优", null));

        mockMvc.perform(get("/api/aqi/overview").param("cityId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.cityName").value("北京"))
                .andExpect(jsonPath("$.data.aqi").value(19))
                .andExpect(jsonPath("$.data.level").value(1))
                .andExpect(jsonPath("$.data.levelLabel").value("优"))
                .andExpect(jsonPath("$.data.monitorDate").exists())
                // AQI<=50 时首要污染物为 null，按 non_null 配置会被省略
                .andExpect(jsonPath("$.data.primaryPollutant").doesNotExist());
    }

    @Test
    @DisplayName("C3 城市不存在 -> HTTP 404 且 code=404")
    void overviewCityNotFound() throws Exception {
        Mockito.when(mockService.getOverview(999)).thenReturn(null);

        mockMvc.perform(get("/api/aqi/overview").param("cityId", "999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("城市不存在: id=999"));
    }

    @Test
    @DisplayName("C4 GET /api/aqi/trend 返回数据点数组，range 可省略（默认 30d）")
    void trend() throws Exception {
        Mockito.when(mockService.getTrend(1, "30d")).thenReturn(List.of(
                new AirQualityService.TrendPointVO(DAY, 19, 11, 19, 28),
                new AirQualityService.TrendPointVO(LocalDate.of(2025, 12, 30), 25, 15, 25, 30)));

        mockMvc.perform(get("/api/aqi/trend").param("cityId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].aqi").value(19))
                .andExpect(jsonPath("$.data[0].pm25").value(11));

        Mockito.verify(mockService).getTrend(1, "30d");
    }

    @Test
    @DisplayName("C4b range 参数需透传给 Service")
    void trendRangePassthrough() throws Exception {
        Mockito.when(mockService.getTrend(5, "7d")).thenReturn(List.of());

        mockMvc.perform(get("/api/aqi/trend").param("cityId", "5").param("range", "7d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        Mockito.verify(mockService).getTrend(5, "7d");
    }

    @Test
    @DisplayName("C5 GET /api/aqi/ranking 返回 12 行榜单")
    void ranking() throws Exception {
        Mockito.when(mockService.getRanking()).thenReturn(List.of(
                new AirQualityService.RankItemVO(1, "北京", 19, 1, "优", null)));

        mockMvc.perform(get("/api/aqi/ranking"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].cityId").value(1))
                .andExpect(jsonPath("$.data[0].cityName").value("北京"))
                .andExpect(jsonPath("$.data[0].levelLabel").value("优"));
    }

    @Test
    @DisplayName("C6 缺少 cityId -> 400 且为标准错误体")
    void missingParam() throws Exception {
        mockMvc.perform(get("/api/aqi/overview"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("C7 cityId 类型错误 -> 400，不出现白页或 500")
    void typeMismatch() throws Exception {
        mockMvc.perform(get("/api/aqi/trend").param("cityId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
