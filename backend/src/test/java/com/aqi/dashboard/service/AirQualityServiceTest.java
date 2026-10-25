package com.aqi.dashboard.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 任务 11 验证（纯单元测试，不连数据库、不启动 Spring 容器）。
 *
 * <p>覆盖三类逻辑：</p>
 * <ol>
 *   <li>等级换算的边界值：50/51、100/101、150/151、200/201、300/301（dataset.md 4.3 指定）；</li>
 *   <li>IAQI 分段插值的锚点与中段值（dataset.md 4.2）；</li>
 *   <li>复算整行数据与入库脚本的一致性，以及取整舍入模式。</li>
 * </ol>
 */
@DisplayName("任务11 - AirQualityService 国标换算")
class AirQualityServiceTest {

    // ==================================================================
    // 等级换算边界
    // ==================================================================

    @Test
    @DisplayName("AQI 六档等级边界：50/51、100/101、150/151、200/201、300/301")
    void levelBoundaries() {
        assertEquals(1, AirQualityService.levelOf(0));
        assertEquals(1, AirQualityService.levelOf(50), "50 应为优");
        assertEquals(2, AirQualityService.levelOf(51), "51 应为良");
        assertEquals(2, AirQualityService.levelOf(100));
        assertEquals(3, AirQualityService.levelOf(101));
        assertEquals(3, AirQualityService.levelOf(150));
        assertEquals(4, AirQualityService.levelOf(151));
        assertEquals(4, AirQualityService.levelOf(200));
        assertEquals(5, AirQualityService.levelOf(201));
        assertEquals(5, AirQualityService.levelOf(300));
        assertEquals(6, AirQualityService.levelOf(301));
        assertEquals(6, AirQualityService.levelOf(500), "500 应为严重污染");
    }

    @Test
    @DisplayName("等级标签与 dataset.md 4.3 完全对应")
    void levelLabels() {
        assertEquals("优", AirQualityService.levelLabelOf(50));
        assertEquals("良", AirQualityService.levelLabelOf(51));
        assertEquals("轻度污染", AirQualityService.levelLabelOf(101));
        assertEquals("中度污染", AirQualityService.levelLabelOf(151));
        assertEquals("重度污染", AirQualityService.levelLabelOf(201));
        assertEquals("严重污染", AirQualityService.levelLabelOf(301));
    }

    // ==================================================================
    // IAQI 分段插值
    // ==================================================================

    @Test
    @DisplayName("分段表锚点：各污染物浓度达到 IAQI=50 的限值时返回 50")
    void iaqiAnchors() {
        assertEquals(50.0, AirQualityService.iaqi("pm25", 35));
        assertEquals(50.0, AirQualityService.iaqi("pm10", 50));
        assertEquals(50.0, AirQualityService.iaqi("so2", 50));
        assertEquals(50.0, AirQualityService.iaqi("no2", 40));
        assertEquals(50.0, AirQualityService.iaqi("co", 2));
        assertEquals(50.0, AirQualityService.iaqi("o3", 100));
    }

    @Test
    @DisplayName("分段表中段线性插值：PM2.5=55 -> IAQI 75")
    void iaqiInterpolation() {
        // (100-50)/(75-35) * (55-35) + 50 = 1.25 * 20 + 50 = 75
        assertEquals(75.0, AirQualityService.iaqi("pm25", 55));
    }

    @Test
    @DisplayName("边界处理：0 浓度返回 0；超出最高分段按 500 封顶")
    void iaqiEdges() {
        assertEquals(0.0, AirQualityService.iaqi("pm25", 0));
        assertEquals(500.0, AirQualityService.iaqi("pm25", 600), "PM2.5 超出 500 上限按 500 封顶");
        assertEquals(500.0, AirQualityService.iaqi("o3", 900), "O3 超出 8h 上限 800 按 500 封顶");
    }

    @Test
    @DisplayName("未知污染物应报参数错误，而不是静默返回 0")
    void unknownPollutant() {
        assertThrows(IllegalArgumentException.class, () -> AirQualityService.iaqi("pm1", 10));
    }

    // ==================================================================
    // 整行复算
    // ==================================================================

    @Test
    @DisplayName("复算已知真实行：北京 2025-01-01 -> AQI 48 优，无首要污染物")
    void computeKnownRow() {
        // db/init.sql 第一行：(1, '2025-01-01', pm25=23, pm10=48, so2=3, no2=37, co=0.5, o3=28, aqi=48, null, 1)
        AirQualityService.AqiComputeResult r =
                AirQualityService.computeAqi(23, 48, 3, 37, new BigDecimal("0.5"), 28);

        assertEquals(48, r.aqi(), "PM10=48 -> IAQI 48 为六项中最大，与库中原值一致");
        assertEquals(1, r.level());
        assertEquals("优", r.levelLabel());
        assertNull(r.primaryPollutant(), "AQI<=50 时无首要污染物");
    }

    @Test
    @DisplayName("AQI 恰为 50 时仍无首要污染物，51 时才出现")
    void primaryPollutantThreshold() {
        // 六项全部落在各自 IAQI=50 的限值上 -> AQI = 50
        AirQualityService.AqiComputeResult at50 =
                AirQualityService.computeAqi(35, 50, 50, 40, new BigDecimal("2"), 100);
        assertEquals(50, at50.aqi());
        assertEquals(1, at50.level());
        assertNull(at50.primaryPollutant());

        // PM2.5 略超 -> IAQI 51.25 -> 取整 51
        AirQualityService.AqiComputeResult at51 =
                AirQualityService.computeAqi(36, 50, 50, 40, new BigDecimal("2"), 100);
        assertEquals(51, at51.aqi());
        assertEquals(2, at51.level());
        assertEquals("PM2.5", at51.primaryPollutant());
    }

    @Test
    @DisplayName("取整必须用 HALF_EVEN 复现入库脚本（Python round）的语义")
    void roundingModeMatchesIngestion() {
        // PM2.5=37 -> IAQI = 1.25 * 2 + 50 = 52.5
        // Python round(52.5) = 52（银行家舍入），Java Math.round 会得 53。
        // 这里必须与 Python 一致，否则"从库里复算"会出现 ±1 偏差。
        AirQualityService.AqiComputeResult r =
                AirQualityService.computeAqi(37, 40, 10, 20, new BigDecimal("0.4"), 20);

        assertEquals(52.5, AirQualityService.iaqi("pm25", 37));
        assertEquals(52, r.aqi(), "半值舍入需向偶数取整，与 db/extract_daily.py 一致");
        assertEquals("PM2.5", r.primaryPollutant());
    }

    @Test
    @DisplayName("六项浓度全部缺失时应抛状态异常，而不是返回一个假的 AQI=0")
    void allMissingThrows() {
        assertThrows(IllegalStateException.class,
                () -> AirQualityService.computeAqi(null, null, null, null, null, null));
    }

    @Test
    @DisplayName("部分缺失时按可见项计算，不因缺项失准")
    void partialMissing() {
        // 只有 PM10=150 -> IAQI 100
        AirQualityService.AqiComputeResult r =
                AirQualityService.computeAqi(null, 150, null, null, null, null);
        assertEquals(100, r.aqi());
        assertEquals("良", r.levelLabel());
        assertEquals("PM10", r.primaryPollutant());
    }
}
