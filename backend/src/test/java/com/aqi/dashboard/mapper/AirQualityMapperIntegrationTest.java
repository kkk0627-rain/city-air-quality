package com.aqi.dashboard.mapper;

import com.aqi.dashboard.entity.AirQuality;
import com.aqi.dashboard.entity.City;
import com.aqi.dashboard.service.AirQualityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务 9 / 10 / 11 的数据层集成验证：直连本机 MySQL 的 {@code aqi_dashboard} 库。
 *
 * <p>与纯单元测试的分工：单测证明<b>算法正确</b>，本测试证明<b>SQL 正确且数据与算法自洽</b>——
 * 其中最关键的一条是最后那个 4,332 行全量交叉校验：用 Java 侧实现的 HJ 633-2012
 * 重新计算每一行，必须与入库时 Python 脚本算出的 aqi / level 逐行一致。</p>
 *
 * <p>前置条件：{@code db/init.sql} 已导入（4,332 行），MySQL 在 localhost:3306 运行。</p>
 */
@SpringBootTest
@DisplayName("任务9/10/11 - Mapper XML 与数据一致性")
class AirQualityMapperIntegrationTest {

    /** 数据集行数（任务 2 导入结论） */
    private static final int EXPECTED_ROWS = 4332;
    private static final int EXPECTED_CITIES = 12;
    private static final LocalDate MAX_DATE = LocalDate.of(2025, 12, 31);

    @Autowired
    private AirQualityMapper airQualityMapper;

    @Autowired
    private AirQualityService airQualityService;

    // ==================================================================
    // 任务 9/10：Mapper 方法与 XML 实现
    // ==================================================================

    @Test
    @DisplayName("M1 findAllCities 返回 12 个城市且字段完整")
    void findAllCities() {
        List<City> cities = airQualityMapper.findAllCities();
        assertEquals(EXPECTED_CITIES, cities.size());
        City first = cities.get(0);
        assertEquals(1, first.getId());
        assertEquals("北京", first.getCityName(), "驼峰映射 city_name -> cityName");
        assertNotNull(first.getProvince());
        assertNotNull(first.getRegion());
    }

    @Test
    @DisplayName("M2 findMaxDate 返回数据集截止日 2025-12-31")
    void findMaxDate() {
        assertEquals(MAX_DATE, airQualityMapper.findMaxDate());
    }

    @Test
    @DisplayName("M3 findLatestByCity 走唯一键命中最新一日")
    void findLatestByCity() {
        AirQuality latest = airQualityMapper.findLatestByCity(1);
        assertNotNull(latest);
        assertEquals(MAX_DATE, latest.getMonitorDate());
        assertEquals(19, latest.getAqi(), "北京 2025-12-31 的 AQI（任务 2 已核对）");
        assertEquals(1, latest.getLevel());
    }

    @Test
    @DisplayName("M3b 不存在的城市返回 null 而非空对象")
    void findLatestByMissingCity() {
        assertNull(airQualityMapper.findLatestByCity(999));
        assertNull(airQualityMapper.findCityById(999));
    }

    @Test
    @DisplayName("M4 findTrend 返回区间内逐日数据且按日期升序")
    void findTrend() {
        List<AirQuality> rows = airQualityMapper.findTrend(
                1, LocalDate.of(2025, 12, 1), MAX_DATE);
        assertEquals(31, rows.size(), "12 月整月无缺测日");
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(LocalDate.of(2025, 12, 1).plusDays(i), rows.get(i).getMonitorDate());
        }
    }

    @Test
    @DisplayName("M4b 区间自动跳过数据集缺失日（2025-06 只应有 27 天）")
    void findTrendSkipsMissingDays() {
        // 2025-06-04 / 06-15 / 06-28 三天因源头数据残缺在清洗阶段被丢弃
        List<AirQuality> rows = airQualityMapper.findTrend(
                1, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30));
        assertEquals(27, rows.size());
        assertFalse(rows.stream().anyMatch(r -> r.getMonitorDate().equals(LocalDate.of(2025, 6, 4))));
    }

    @Test
    @DisplayName("M5 findRankingByDate 返回 12 城同日快照，按 AQI 升序且带城市名")
    void findRankingByDate() {
        List<AirQuality> rows = airQualityMapper.findRankingByDate(MAX_DATE);
        assertEquals(EXPECTED_CITIES, rows.size());
        for (AirQuality row : rows) {
            assertNotNull(row.getCityName(), "排行榜需 JOIN city 取到城市名");
            assertEquals(MAX_DATE, row.getMonitorDate());
        }
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i - 1).getAqi() <= rows.get(i).getAqi(), "必须按 AQI 升序");
        }
    }

    // ==================================================================
    // 任务 11：Service 聚合
    // ==================================================================

    @Test
    @DisplayName("S1 getOverview 组装概览卡字段")
    void getOverview() {
        AirQualityService.OverviewVO vo = airQualityService.getOverview(1);
        assertNotNull(vo);
        assertEquals("北京", vo.cityName());
        assertEquals(MAX_DATE, vo.monitorDate());
        assertEquals(19, vo.aqi());
        assertEquals("优", vo.levelLabel());
        assertNull(vo.primaryPollutant(), "AQI<=50 时首要污染物为空");
    }

    @Test
    @DisplayName("S2 getOverview 对不存在的城市返回 null（由 Controller 转 404）")
    void getOverviewMissingCity() {
        assertNull(airQualityService.getOverview(999));
    }

    @Test
    @DisplayName("S3 getTrend 三种窗口的数据点数与端点")
    void getTrendWindows() {
        List<AirQualityService.TrendPointVO> week = airQualityService.getTrend(1, "7d");
        assertEquals(7, week.size());
        assertEquals(MAX_DATE, week.get(week.size() - 1).date(), "末点必须是最新一日");

        List<AirQualityService.TrendPointVO> month = airQualityService.getTrend(1, "30d");
        assertEquals(30, month.size());

        List<AirQualityService.TrendPointVO> year = airQualityService.getTrend(1, "12m");
        assertEquals(361, year.size(), "近 12 个月覆盖全年且有 4 天缺失");
        assertEquals(LocalDate.of(2025, 1, 1), year.get(0).date());
    }

    @Test
    @DisplayName("S4 getTrend 的非法区间应抛参数异常")
    void getTrendInvalidRange() {
        assertThrows(IllegalArgumentException.class, () -> airQualityService.getTrend(1, "90d"));
    }

    @Test
    @DisplayName("S5 getRanking 返回 12 城升序榜单")
    void getRanking() {
        List<AirQualityService.RankItemVO> ranking = airQualityService.getRanking();
        assertEquals(EXPECTED_CITIES, ranking.size());
        for (int i = 1; i < ranking.size(); i++) {
            assertTrue(ranking.get(i - 1).aqi() <= ranking.get(i).aqi());
        }
        AirQualityService.RankItemVO top = ranking.get(0);
        assertEquals(AirQualityService.levelLabelOf(top.aqi()), top.levelLabel(), "等级标签需与 AQI 自洽");
    }

    // ==================================================================
    // 全量交叉校验：Java 复算 vs 入库数据
    // ==================================================================

    @Test
    @DisplayName("X1 全部 4,332 行：Java 按国标复算的 AQI/等级必须与入库值完全一致")
    void crossCheckAllRows() {
        List<String> mismatches = new ArrayList<>();
        int rows = 0;
        for (int cityId = 1; cityId <= EXPECTED_CITIES; cityId++) {
            List<AirQuality> yearly = airQualityMapper.findTrend(
                    cityId, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
            for (AirQuality row : yearly) {
                rows++;
                AirQualityService.AqiComputeResult computed = AirQualityService.computeAqi(
                        row.getPm25(), row.getPm10(), row.getSo2(),
                        row.getNo2(), row.getCo(), row.getO3());
                if (computed.aqi() != row.getAqi()
                        || computed.level() != row.getLevel()
                        || !java.util.Objects.equals(computed.primaryPollutant(), row.getPrimaryPollutant())) {
                    if (mismatches.size() < 10) {
                        mismatches.add(String.format("cityId=%d %s 库(aqi=%d,level=%d,primary=%s) vs 算(aqi=%d,level=%d,primary=%s)",
                                cityId, row.getMonitorDate(),
                                row.getAqi(), row.getLevel(), row.getPrimaryPollutant(),
                                computed.aqi(), computed.level(), computed.primaryPollutant()));
                    }
                }
            }
        }
        assertEquals(EXPECTED_ROWS, rows, "参与校验的行数应等于数据集总量");
        assertTrue(mismatches.isEmpty(),
                "Java 复算与入库数据不一致的行：\n" + String.join("\n", mismatches));
    }
}
