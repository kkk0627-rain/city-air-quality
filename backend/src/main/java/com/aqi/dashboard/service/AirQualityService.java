package com.aqi.dashboard.service;

import com.aqi.dashboard.entity.AirQuality;
import com.aqi.dashboard.entity.City;
import com.aqi.dashboard.mapper.AirQualityMapper;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 空气质量看板业务逻辑层 —— 任务 11 交付物。
 *
 * <p>职责分两块：</p>
 * <ol>
 *   <li><b>国标换算</b>：{@link #levelOf(int)} / {@link #levelLabelOf(int)} 按 HJ 633-2012 六档分级；
 *       {@link #computeAqi} 按日均分段表复算 AQI 与首要污染物（静态方法，不依赖 Spring 容器，可直接单测）；</li>
 *   <li><b>看板聚合</b>：把 Mapper 返回的实体组装成 {@code docs/dataset.md} 第 9 节约定的 JSON 契约。</li>
 * </ol>
 *
 * <p><b>为什么 DTO 写成嵌套 record</b>：任务清单要求"一个任务只改一个文件"，
 * 若为每个 VO 新建 .java 文件就会超出该约束，故将其声明为本类的静态成员类型。
 * 它们是不可变 record，Jackson 可直接序列化，Controller 通过 {@code AirQualityService.OverviewVO} 引用。</p>
 */
@Service
public class AirQualityService {

    /** 趋势区间：近 7 天 */
    public static final String RANGE_7D = "7d";
    /** 趋势区间：近 30 天 */
    public static final String RANGE_30D = "30d";
    /** 趋势区间：近 12 个月 */
    public static final String RANGE_12M = "12m";

    /** 分页上限：防止 size 被放大成全表扫描 */
    public static final int MAX_PAGE_SIZE = 100;

    /** 默认指标 */
    public static final String METRIC_AQI = "aqi";

    /** 六档等级标签，下标 = level - 1（docs/dataset.md 4.3） */
    private static final String[] LEVEL_LABELS = {"优", "良", "轻度污染", "中度污染", "重度污染", "严重污染"};

    /**
     * 指标元信息：对外键、展示名、单位。
     *
     * <p>指标切换（F4）的可选项与单位显示都由这里驱动，
     * 前端与后端共用同一份定义，避免出现"图上有这个指标但接口不认"的情况。</p>
     */
    public record MetricMeta(String key, String label, String unit) {
    }

    /**
     * 可查询指标表。
     *
     * <p><b>必须用 LinkedHashMap</b>：它决定前端指标切换按钮的排列顺序，
     * 用 HashMap 会导致每次启动顺序抖动。</p>
     */
    public static final Map<String, MetricMeta> METRICS = new LinkedHashMap<>();

    static {
        METRICS.put("aqi", new MetricMeta("aqi", "AQI", ""));
        METRICS.put("pm25", new MetricMeta("pm25", "PM2.5", "μg/m³"));
        METRICS.put("pm10", new MetricMeta("pm10", "PM10", "μg/m³"));
        METRICS.put("so2", new MetricMeta("so2", "SO₂", "μg/m³"));
        METRICS.put("no2", new MetricMeta("no2", "NO₂", "μg/m³"));
        METRICS.put("co", new MetricMeta("co", "CO", "mg/m³"));
        METRICS.put("o3", new MetricMeta("o3", "O₃", "μg/m³"));
    }

    private final AirQualityMapper airQualityMapper;

    public AirQualityService(AirQualityMapper airQualityMapper) {
        this.airQualityMapper = airQualityMapper;
    }

    // ==================================================================
    // 看板聚合：城市列表 / 概览卡 / 趋势 / 排行榜
    // ==================================================================

    /**
     * 城市列表（下拉框数据源）。
     *
     * @return 12 个城市，按 id 升序
     */
    public List<CityBriefVO> getCities() {
        return airQualityMapper.findAllCities().stream()
                // code 复用主键：见 CityBriefVO 的口径说明
                .map(c -> new CityBriefVO(c.getId(), c.getCityName(), c.getProvince(), c.getRegion()))
                .toList();
    }

    /**
     * 概览卡：某城市在最新监测日的 AQI 快照。
     *
     * @param cityId 城市编号
     * @return 快照；<b>城市不存在时返回 null</b>，由 Controller 转成 404
     */
    public OverviewVO getOverview(int cityId) {
        City city = airQualityMapper.findCityById(cityId);
        if (city == null) {
            return null;
        }
        AirQuality latest = airQualityMapper.findLatestByCity(cityId);
        if (latest == null) {
            return null;
        }
        int aqi = latest.getAqi();
        return new OverviewVO(
                city.getCityName(),
                latest.getMonitorDate(),
                aqi,
                levelOf(aqi),
                levelLabelOf(aqi),
                latest.getPrimaryPollutant()
        );
    }

    /**
     * 趋势折线图：某城市近 7 天 / 30 天 / 12 个月的逐日数据。
     *
     * <p>窗口定义（含最新日在内）：{@code 7d} = 最近 7 天、{@code 30d} = 最近 30 天、
     * {@code 12m} = 自去年同日的次日起至今。数据集缺失的日期在结果中直接缺席，
     * 前端按 {@code date} 字段直出 X 轴即可保证时间轴连续真实。</p>
     *
     * @param cityId 城市编号
     * @param range  区间标识，见 {@link #RANGE_7D} / {@link #RANGE_30D} / {@link #RANGE_12M}
     * @return 按日期升序的数据点；<b>城市不存在时返回 null</b>
     * @throws IllegalArgumentException range 非法时抛出（由全局异常处理为 400）
     */
    public List<TrendPointVO> getTrend(int cityId, String range) {
        // 先校验区间再查库：参数非法时不该白白消耗一次 MAX(monitor_date) 查询
        String normalized = requireRange(range);
        LocalDate endDate = requireMaxDate();
        LocalDate startDate = startOf(endDate, normalized);
        City city = airQualityMapper.findCityById(cityId);
        if (city == null) {
            return null;
        }
        List<AirQuality> rows = airQualityMapper.findTrend(cityId, startDate, endDate);
        List<TrendPointVO> points = new ArrayList<>(rows.size());
        for (AirQuality row : rows) {
            points.add(new TrendPointVO(
                    row.getMonitorDate(),
                    row.getAqi(),
                    row.getPm25(),
                    row.getPm10(),
                    row.getO3()
            ));
        }
        return points;
    }

    /**
     * 排行榜：全部城市在同一监测日（最新日）的 AQI 快照，按 AQI 升序。
     *
     * @return 12 行榜单，"最干净"的城市排第一
     */
    public List<RankItemVO> getRanking() {
        LocalDate maxDate = requireMaxDate();
        List<AirQuality> rows = airQualityMapper.findRankingByDate(maxDate);
        List<RankItemVO> items = new ArrayList<>(rows.size());
        for (AirQuality row : rows) {
            int aqi = row.getAqi();
            items.add(new RankItemVO(
                    row.getCityId(),
                    row.getCityName(),
                    aqi,
                    levelOf(aqi),
                    levelLabelOf(aqi),
                    row.getPrimaryPollutant()
            ));
        }
        return items;
    }

    // ==================================================================
    // 需求 A2~A4：明细分页查询 / 分组统计 / 单条详情
    // 契约见 docs/dataset.md 第 11 节
    // ==================================================================

    /**
     * 数据集元信息：真实日期边界 + 可查询指标列表。
     *
     * <p>供前端日期控件设置 {@code min/max} 与默认值——数据集截止于 2025-12-31，
     * 与系统当前日期无关，必须以下发值为准。</p>
     *
     * @return 元信息；空表时两个日期均为 null
     */
    public DatasetMetaVO getDatasetMeta() {
        return new DatasetMetaVO(
                airQualityMapper.findMinDate(),
                airQualityMapper.findMaxDate(),
                List.copyOf(METRICS.values()),
                METRIC_AQI
        );
    }

    /**
     * 多城市趋势序列（F5 折线图数据源）。
     *
     * <p>底层与 A2 同一套筛选逻辑，只是把结果<b>按城市排列成时间序列</b>，
     * 而不是分页明细——折线图需要的是"每个城市一条完整曲线"，
     * 分页结构给不了这个形状，因此在这里做一次变形。</p>
     *
     * <p>时间轴取区间内出现过的所有日期（升序去重）。某城市在某日缺测时，
     * 对应位置为 null，前端设 {@code connectNulls: false} 即断线显示，
     * 不会把缺失点连成虚假趋势。</p>
     *
     * @param cityIds 城市编号集合；null / 空 = 全部城市
     * @param metric  指标键
     * @param start   起始日期（含）
     * @param end     截止日期（含）
     * @return 时间轴 + 各城市数值序列
     */
    public SeriesVO getSeries(List<Integer> cityIds, String metric,
                              LocalDate start, LocalDate end) {
        String safeMetric = requireMetric(metric);
        LocalDate[] range = resolveRange(start, end);
        List<Integer> safeCityIds = normalizeCityIds(cityIds);

        List<AirQuality> rows = airQualityMapper.findStatsByConditions(safeCityIds, range[0], range[1]);

        List<LocalDate> dates = rows.stream()
                .map(AirQuality::getMonitorDate)
                .distinct()
                .sorted()
                .toList();

        Map<Integer, List<AirQuality>> grouped = new LinkedHashMap<>();
        for (AirQuality row : rows) {
            grouped.computeIfAbsent(row.getCityId(), k -> new ArrayList<>()).add(row);
        }

        List<CitySeriesVO> series = new ArrayList<>(grouped.size());
        for (Map.Entry<Integer, List<AirQuality>> entry : grouped.entrySet()) {
            List<AirQuality> cityRows = entry.getValue();
            Map<LocalDate, BigDecimal> byDate = new HashMap<>(cityRows.size());
            for (AirQuality row : cityRows) {
                byDate.put(row.getMonitorDate(), metricValue(row, safeMetric));
            }
            // 缺失日期保留为 null，交给前端断线处理
            List<BigDecimal> values = dates.stream().map(byDate::get).toList();
            series.add(new CitySeriesVO(entry.getKey(), cityRows.get(0).getCityName(), values));
        }
        return new SeriesVO(
                safeMetric,
                METRICS.get(safeMetric).label(),
                METRICS.get(safeMetric).unit(),
                dates,
                series
        );
    }

    /**
     * A2：按城市集合 + 指标 + 日期区间查询明细分页（F8 详情表格数据源）。
     *
     * <p>参数容错策略：城市为空 = 全部城市；起止日期为空 = 数据集全域；
     * 超出数据集边界的日期<b>钳制</b>到边界而非报错；仅当 {@code start > end} 时抛 400。</p>
     *
     * @param cityIds 城市编号集合；null / 空 = 全部城市
     * @param metric  指标键，见 {@link #METRICS}；null / 空 = {@code aqi}
     * @param start   起始日期（含），null = 数据集首日
     * @param end     截止日期（含），null = 数据集末日
     * @param page    页码，从 1 起（小于 1 自动纠正为 1）
     * @param size    每页条数（纠正到 1~{@link #MAX_PAGE_SIZE}）
     * @return 分页结果，{@code list} 按日期倒序
     * @throws IllegalArgumentException 指标非法或 start > end（由全局异常处理为 400）
     */
    public PageResult<RecordVO> queryRecords(List<Integer> cityIds, String metric,
                                             LocalDate start, LocalDate end, int page, int size) {
        String safeMetric = requireMetric(metric);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        LocalDate[] range = resolveRange(start, end);
        List<Integer> safeCityIds = normalizeCityIds(cityIds);

        long total = airQualityMapper.countByConditions(safeCityIds, range[0], range[1]);
        if (total == 0) {
            return new PageResult<>(0L, safePage, safeSize, List.of());
        }
        long offset = (long) (safePage - 1) * safeSize;
        List<RecordVO> list = airQualityMapper
                .findByConditions(safeCityIds, range[0], range[1], offset, safeSize)
                .stream()
                .map(row -> toRecordVO(row, safeMetric))
                .toList();
        return new PageResult<>(total, safePage, safeSize, list);
    }

    /**
     * A3：按城市分组统计某指标在区间内的 avg / max / min 与优良天数占比（F6、F7 数据源）。
     *
     * <p>口径：{@code samples} = 该城市区间内总行数；avg/max/min 只对非空指标值计算；
     * {@code goodRatio} = level≤2（优+良）天数 / samples × 100，保留 1 位小数；
     * {@code levelCounts} 给出 1~6 档各自天数，供等级分布环形图直接使用。</p>
     *
     * <p><b>为什么在 Java 侧聚合而不是 SQL GROUP BY</b>：见
     * {@code AirQualityMapper} 接口注释。核心是避免 XML 里拼列名，
     * 并让"等级"口径只有 {@link #levelOf} 一份实现。</p>
     *
     * @param cityIds 城市编号集合；null / 空 = 全部城市
     * @param metric  指标键
     * @param start   起始日期（含）
     * @param end     截止日期（含）
     * @return 每城一行统计，按城市编号升序
     */
    public List<StatsVO> queryStats(List<Integer> cityIds, String metric,
                                    LocalDate start, LocalDate end) {
        String safeMetric = requireMetric(metric);
        LocalDate[] range = resolveRange(start, end);
        List<Integer> safeCityIds = normalizeCityIds(cityIds);

        List<AirQuality> rows = airQualityMapper.findStatsByConditions(safeCityIds, range[0], range[1]);

        Map<Integer, List<AirQuality>> grouped = new LinkedHashMap<>();
        for (AirQuality row : rows) {
            grouped.computeIfAbsent(row.getCityId(), k -> new ArrayList<>()).add(row);
        }

        List<StatsVO> result = new ArrayList<>(grouped.size());
        for (Map.Entry<Integer, List<AirQuality>> entry : grouped.entrySet()) {
            List<AirQuality> cityRows = entry.getValue();

            List<BigDecimal> values = new ArrayList<>(cityRows.size());
            Map<Integer, Integer> levelCounts = new LinkedHashMap<>();
            for (int level = 1; level <= 6; level++) {
                levelCounts.put(level, 0);
            }
            int goodDays = 0;

            for (AirQuality row : cityRows) {
                BigDecimal value = metricValue(row, safeMetric);
                if (value != null) {
                    values.add(value);
                }
                Integer level = row.getLevel();
                if (level != null && level >= 1 && level <= 6) {
                    levelCounts.merge(level, 1, Integer::sum);
                    if (level <= 2) {
                        goodDays++;
                    }
                }
            }

            BigDecimal avg = null;
            BigDecimal max = null;
            BigDecimal min = null;
            if (!values.isEmpty()) {
                BigDecimal sum = BigDecimal.ZERO;
                max = values.get(0);
                min = values.get(0);
                for (BigDecimal value : values) {
                    sum = sum.add(value);
                    max = max.max(value);
                    min = min.min(value);
                }
                // 展示用 HALF_UP 是刻意的：与 computeAqi 的 HALF_EVEN 不同——
                // 后者为了复现 Python round 生成的入库整数，此处是派生统计，面向人眼阅读。
                avg = sum.divide(BigDecimal.valueOf(values.size()), 1, RoundingMode.HALF_UP);
            }

            int samples = cityRows.size();
            BigDecimal goodRatio = samples == 0 ? null
                    : BigDecimal.valueOf(goodDays * 100L)
                            .divide(BigDecimal.valueOf(samples), 1, RoundingMode.HALF_UP);

            result.add(new StatsVO(
                    entry.getKey(),
                    cityRows.get(0).getCityName(),
                    safeMetric,
                    METRICS.get(safeMetric).unit(),
                    avg, max, min,
                    goodRatio, goodDays, samples,
                    levelCounts
            ));
        }
        return result;
    }

    // ==================================================================
    // 综合分析（扩展能力）：综合指数 + 排名 + 多维对比/分类
    //
    // A1~A4 只回答"某个指标是多少"，看板要做"哪座城市更值得去 / 什么时候最差"
    // 这类判断还缺三样东西：一个能横向比较的综合数、一个分类维度、一个季节维度。
    // 这里一次性补齐，前端的「对比」「分类」两个 Tab 全部由它驱动。
    // ==================================================================

    /**
     * 环境空气质量综合指数（Composite Index，简称 CI）的评价因子与限值。
     *
     * <p>口径来自生态环境部《城市环境空气质量排名技术规定》：
     * <b>单项质量指数 = 污染物浓度 / 该污染物年均值二级标准限值</b>（GB 3095-2012），
     * <b>综合指数 = 六项单项质量指数之和</b>。与 AQI 不同，它不是"取最大"而是"求和"，
     * 因此能反映多种污染物叠加的累负担，适合做城际横向排名。</p>
     *
     * <p><b>数值越小越清洁</b>（与 AQI 同向、与"评分"反向），这一点在前端必须标注清楚，
     * 否则会被误读成"分数越高越好"。</p>
     *
     * <p>关于 O₃：官方年度评价用"日最大 8 小时滑动平均的第 90 百分位数"，
     * 而本项目数据集提供的是日均浓度，故这里按日均值参与计算。
     * 日均值通常低于日最大 8h，因此本指数的绝对值与官方发布值不可直接比较，
     * <b>仅用于本项目内部的城际横向对比</b>。</p>
     */
    private static final Map<String, BigDecimal> CI_LIMITS = new LinkedHashMap<>();

    static {
        CI_LIMITS.put("pm25", BigDecimal.valueOf(35));
        CI_LIMITS.put("pm10", BigDecimal.valueOf(70));
        CI_LIMITS.put("so2", BigDecimal.valueOf(60));
        CI_LIMITS.put("no2", BigDecimal.valueOf(40));
        CI_LIMITS.put("co", BigDecimal.valueOf(4));
        CI_LIMITS.put("o3", BigDecimal.valueOf(160));
    }

    /** 气象学季节划分：12~2 冬、3~5 春、6~8 夏、9~11 秋 */
    private static String seasonOf(int month) {
        if (month >= 3 && month <= 5) return "春";
        if (month >= 6 && month <= 8) return "夏";
        if (month >= 9 && month <= 11) return "秋";
        return "冬";
    }

    /** 列表均值；空列表返回 null（而不是 0，避免把"缺测"画成"很干净"） */
    private static BigDecimal safeAvg(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : values) sum = sum.add(value);
        return sum.divide(BigDecimal.valueOf(values.size()), 1, RoundingMode.HALF_UP);
    }

    /** 单项质量指数 = 浓度 / 限值；浓度缺测时返回 null，不按 0 计入综合指数 */
    private static BigDecimal ratioOf(BigDecimal value, String factor) {
        if (value == null) return null;
        return value.divide(CI_LIMITS.get(factor), 3, RoundingMode.HALF_UP);
    }

    /**
     * 综合分析：一次返回看板「对比」「分类」两个 Tab 所需的全部聚合结果。
     *
     * <p>与 A3 共用 {@code findStatsByConditions} 的结果集，在 Java 侧做二次聚合，
     * 不新增 SQL —— 12 城 × 361 天 = 4,332 行全量加载的成本可以忽略，
     * 换来的是"等级""季节""综合指数"这些口径只在此处有一份实现。</p>
     *
     * @param cityIds 城市编号集合；null / 空 = 全部城市
     * @param start   起始日期（含）
     * @param end     截止日期（含）
     * @return 综合分析结果；<b>区间内无数据时返回空结构而不是 null</b>
     */
    public AnalysisVO getAnalysis(List<Integer> cityIds, LocalDate start, LocalDate end) {
        LocalDate[] range = resolveRange(start, end);
        List<Integer> safeCityIds = normalizeCityIds(cityIds);
        List<AirQuality> rows = airQualityMapper.findStatsByConditions(safeCityIds, range[0], range[1]);

        Map<Integer, City> cityMap = new LinkedHashMap<>();
        for (City city : airQualityMapper.findAllCities()) {
            cityMap.put(city.getId(), city);
        }

        // ---- 1) 按城市：六项浓度均值 -> 单项质量指数 -> 综合指数 -> 排名 ----
        Map<Integer, List<AirQuality>> byCity = new LinkedHashMap<>();
        for (AirQuality row : rows) {
            byCity.computeIfAbsent(row.getCityId(), k -> new ArrayList<>()).add(row);
        }

        List<CityCompositeVO> composites = new ArrayList<>(byCity.size());
        for (Map.Entry<Integer, List<AirQuality>> entry : byCity.entrySet()) {
            Integer code = entry.getKey();
            List<AirQuality> list = entry.getValue();

            List<BigDecimal> pm25s = new ArrayList<>();
            List<BigDecimal> pm10s = new ArrayList<>();
            List<BigDecimal> so2s = new ArrayList<>();
            List<BigDecimal> no2s = new ArrayList<>();
            List<BigDecimal> cos = new ArrayList<>();
            List<BigDecimal> o3s = new ArrayList<>();
            List<BigDecimal> aqis = new ArrayList<>();
            int goodDays = 0;

            for (AirQuality r : list) {
                if (r.getPm25() != null) pm25s.add(BigDecimal.valueOf(r.getPm25()));
                if (r.getPm10() != null) pm10s.add(BigDecimal.valueOf(r.getPm10()));
                if (r.getSo2() != null) so2s.add(BigDecimal.valueOf(r.getSo2()));
                if (r.getNo2() != null) no2s.add(BigDecimal.valueOf(r.getNo2()));
                if (r.getCo() != null) cos.add(r.getCo());
                if (r.getO3() != null) o3s.add(BigDecimal.valueOf(r.getO3()));
                if (r.getAqi() != null) aqis.add(BigDecimal.valueOf(r.getAqi()));
                Integer lv = r.getLevel();
                if (lv != null && lv >= 1 && lv <= 2) goodDays++;
            }

            BigDecimal pm25 = safeAvg(pm25s);
            BigDecimal pm10 = safeAvg(pm10s);
            BigDecimal so2 = safeAvg(so2s);
            BigDecimal no2 = safeAvg(no2s);
            BigDecimal co = safeAvg(cos);
            BigDecimal o3 = safeAvg(o3s);
            BigDecimal aqi = safeAvg(aqis);

            Map<String, BigDecimal> ratios = new LinkedHashMap<>();
            ratios.put("pm25", ratioOf(pm25, "pm25"));
            ratios.put("pm10", ratioOf(pm10, "pm10"));
            ratios.put("so2", ratioOf(so2, "so2"));
            ratios.put("no2", ratioOf(no2, "no2"));
            ratios.put("co", ratioOf(co, "co"));
            ratios.put("o3", ratioOf(o3, "o3"));

            BigDecimal ci = null;
            if (!ratios.containsValue(null)) {
                BigDecimal sum = BigDecimal.ZERO;
                for (BigDecimal v : ratios.values()) sum = sum.add(v);
                ci = sum.setScale(2, RoundingMode.HALF_UP);
            }

            int samples = list.size();
            BigDecimal goodRatio = samples == 0 ? null
                    : BigDecimal.valueOf(goodDays * 100L)
                            .divide(BigDecimal.valueOf(samples), 1, RoundingMode.HALF_UP);

            City city = cityMap.get(code);
            composites.add(new CityCompositeVO(
                    code,
                    list.get(0).getCityName(),
                    city == null ? "" : city.getProvince(),
                    city == null ? "未分类" : (city.getRegion() == null ? "未分类" : city.getRegion()),
                    pm25, pm10, so2, no2, co, o3, aqi,
                    ci, goodRatio, goodDays, samples,
                    0, ratios
            ));
        }

        // 排名：CI 升序（越小越清洁）为第 1 名；CI 缺失的城市排在最后
        List<CityCompositeVO> ranked = composites.stream()
                .sorted((a, b) -> {
                    BigDecimal ac = a.compositeIndex();
                    BigDecimal bc = b.compositeIndex();
                    if (ac == null && bc == null) return 0;
                    if (ac == null) return 1;
                    if (bc == null) return -1;
                    return ac.compareTo(bc);
                })
                .toList();
        List<CityCompositeVO> withRank = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            CityCompositeVO c = ranked.get(i);
            withRank.add(new CityCompositeVO(
                    c.code(), c.city(), c.province(), c.region(),
                    c.pm25(), c.pm10(), c.so2(), c.no2(), c.co(), c.o3(), c.aqi(),
                    c.compositeIndex(), c.goodRatio(), c.goodDays(), c.samples(),
                    i + 1, c.ratios()
            ));
        }

        // ---- 2) 城市 × 月份：热力图 ----
        Map<String, List<BigDecimal>> monthCells = new LinkedHashMap<>();
        Map<String, String> monthCity = new LinkedHashMap<>();
        Map<String, List<BigDecimal>> monthOverall = new LinkedHashMap<>();
        for (AirQuality r : rows) {
            if (r.getMonitorDate() == null || r.getAqi() == null) continue;
            String month = r.getMonitorDate().getYear() + "-"
                    + String.format("%02d", r.getMonitorDate().getMonthValue());
            String key = r.getCityId() + "|" + month;
            monthCells.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(BigDecimal.valueOf(r.getAqi()));
            monthCity.putIfAbsent(key, r.getCityName());
            monthOverall.computeIfAbsent(month, k -> new ArrayList<>())
                    .add(BigDecimal.valueOf(r.getAqi()));
        }
        List<MonthCellVO> monthly = new ArrayList<>(monthCells.size());
        for (String key : monthCells.keySet()) {
            String[] parts = key.split("\\|");
            BigDecimal avg = safeAvg(monthCells.get(key));
            if (avg == null) continue;
            monthly.add(new MonthCellVO(Integer.valueOf(parts[0]), monthCity.get(key), parts[1], avg));
        }
        List<MonthPointVO> monthlyTrend = new ArrayList<>(monthOverall.size());
        for (String month : monthOverall.keySet()) {
            BigDecimal avg = safeAvg(monthOverall.get(month));
            if (avg != null) {
                monthlyTrend.add(new MonthPointVO(month, avg, monthOverall.get(month).size()));
            }
        }

        // ---- 3) 按区域 / 季节分类 ----
        Map<String, List<AirQuality>> byRegion = new LinkedHashMap<>();
        Map<String, List<AirQuality>> bySeason = new LinkedHashMap<>();
        for (AirQuality r : rows) {
            City c = cityMap.get(r.getCityId());
            String region = (c == null || c.getRegion() == null) ? "未分类" : c.getRegion();
            byRegion.computeIfAbsent(region, k -> new ArrayList<>()).add(r);
            if (r.getMonitorDate() != null) {
                bySeason.computeIfAbsent(seasonOf(r.getMonitorDate().getMonthValue()),
                        k -> new ArrayList<>()).add(r);
            }
        }

        // 区域的综合指数取其成员城市 CI 的均值：CI 是比值量，直接对原始行再求平均没有意义
        Map<String, List<BigDecimal>> regionCis = new LinkedHashMap<>();
        for (CityCompositeVO c : withRank) {
            if (c.compositeIndex() == null) continue;
            regionCis.computeIfAbsent(c.region(), k -> new ArrayList<>()).add(c.compositeIndex());
        }

        List<GroupVO> regions = new ArrayList<>(byRegion.size());
        for (Map.Entry<String, List<AirQuality>> entry : byRegion.entrySet()) {
            List<AirQuality> list = entry.getValue();
            List<BigDecimal> aqis = new ArrayList<>();
            int goodDays = 0;
            Set<Integer> cities = new LinkedHashSet<>();
            for (AirQuality r : list) {
                if (r.getAqi() != null) aqis.add(BigDecimal.valueOf(r.getAqi()));
                Integer lv = r.getLevel();
                if (lv != null && lv >= 1 && lv <= 2) goodDays++;
                cities.add(r.getCityId());
            }
            int samples = list.size();
            regions.add(new GroupVO(
                    entry.getKey(),
                    cities.size(),
                    safeAvg(aqis),
                    safeAvg(regionCis.get(entry.getKey())),
                    samples == 0 ? null : BigDecimal.valueOf(goodDays * 100L)
                            .divide(BigDecimal.valueOf(samples), 1, RoundingMode.HALF_UP),
                    samples
            ));
        }
        regions.sort(Comparator.comparing(GroupVO::name));

        // 季节按固定顺序输出，否则前端图例顺序会随数据变化抖动
        List<String> seasonOrder = List.of("春", "夏", "秋", "冬");
        List<GroupVO> seasons = new ArrayList<>();
        for (String season : seasonOrder) {
            List<AirQuality> list = bySeason.get(season);
            if (list == null || list.isEmpty()) continue;
            List<BigDecimal> aqis = new ArrayList<>();
            int goodDays = 0;
            Set<Integer> cities = new LinkedHashSet<>();
            for (AirQuality r : list) {
                if (r.getAqi() != null) aqis.add(BigDecimal.valueOf(r.getAqi()));
                Integer lv = r.getLevel();
                if (lv != null && lv >= 1 && lv <= 2) goodDays++;
                cities.add(r.getCityId());
            }
            int samples = list.size();
            seasons.add(new GroupVO(
                    season, cities.size(), safeAvg(aqis), null,
                    samples == 0 ? null : BigDecimal.valueOf(goodDays * 100L)
                            .divide(BigDecimal.valueOf(samples), 1, RoundingMode.HALF_UP),
                    samples
            ));
        }

        // ---- 4) 首要污染物分布 / 等级分布 ----
        Map<String, Integer> pollutantCounts = new LinkedHashMap<>();
        Map<Integer, Integer> levelCounts = new LinkedHashMap<>();
        for (int level = 1; level <= 6; level++) levelCounts.put(level, 0);
        for (AirQuality r : rows) {
            String name = (r.getPrimaryPollutant() == null || r.getPrimaryPollutant().isBlank())
                    ? "无" : r.getPrimaryPollutant();
            pollutantCounts.merge(name, 1, Integer::sum);
            Integer lv = r.getLevel();
            if (lv != null && lv >= 1 && lv <= 6) levelCounts.merge(lv, 1, Integer::sum);
        }
        int totalRows = rows.size();
        List<ShareVO> pollutants = pollutantCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .map(e -> new ShareVO(e.getKey(), e.getValue(),
                        totalRows == 0 ? null : BigDecimal.valueOf(e.getValue() * 100L)
                                .divide(BigDecimal.valueOf(totalRows), 1, RoundingMode.HALF_UP)))
                .toList();
        List<ShareVO> levels = new ArrayList<>();
        for (int level = 1; level <= 6; level++) {
            int days = levelCounts.get(level);
            levels.add(new ShareVO(LEVEL_LABELS[level - 1], days,
                    totalRows == 0 ? null : BigDecimal.valueOf(days * 100L)
                            .divide(BigDecimal.valueOf(totalRows), 1, RoundingMode.HALF_UP)));
        }

        return new AnalysisVO(
                range[0] == null ? null : range[0].toString(),
                range[1] == null ? null : range[1].toString(),
                totalRows,
                withRank, monthly, monthlyTrend, regions, seasons, pollutants, levels
        );
    }

    /**
     * A4：按主键查询单条日报详情。
     *
     * @param id 记录主键
     * @return 详情；<b>记录不存在时返回 null</b>，由 Controller 转成 404
     */
    public RecordDetailVO getRecordDetail(long id) {
        AirQuality row = airQualityMapper.findRecordById(id);
        if (row == null) {
            return null;
        }
        int aqi = row.getAqi();
        return new RecordDetailVO(
                row.getId(),
                row.getCityId(),
                row.getCityName(),
                row.getMonitorDate(),
                row.getPm25(), row.getPm10(), row.getSo2(), row.getNo2(),
                row.getCo(), row.getO3(),
                aqi, row.getPrimaryPollutant(),
                levelOf(aqi), levelLabelOf(aqi)
        );
    }

    // ==================================================================
    // 国标分级（HJ 633-2012）—— 纯函数，可脱离容器单测
    // ==================================================================

    /**
     * AQI → 等级 1~6。
     *
     * <p>边界：0–50 优 / 51–100 良 / 101–150 轻度 / 151–200 中度 / 201–300 重度 / &gt;300 严重。</p>
     *
     * @param aqi 空气质量指数
     * @return 1~6 的等级编号
     */
    public static int levelOf(int aqi) {
        if (aqi <= 50) {
            return 1;
        }
        if (aqi <= 100) {
            return 2;
        }
        if (aqi <= 150) {
            return 3;
        }
        if (aqi <= 200) {
            return 4;
        }
        if (aqi <= 300) {
            return 5;
        }
        return 6;
    }

    /**
     * AQI → 等级中文标签。
     *
     * @param aqi 空气质量指数
     * @return 如"良""轻度污染"
     */
    public static String levelLabelOf(int aqi) {
        return LEVEL_LABELS[levelOf(aqi) - 1];
    }

    // ==================================================================
    // IAQI 复算（HJ 633-2012 日均分段表）
    // ==================================================================

    /**
     * 日均浓度 IAQI 分段表：{@code 污染物 -> {浓度上限序列, 对应 IAQI 序列}}。
     *
     * <p>取自 {@code docs/dataset.md} 4.2。O3 只到 IAQI=300（8h 上限 800 μg/m³），
     * 超出后与其他污染物一致按 500 封顶，与 Python 清洗脚本 {@code db/extract_daily.py} 保持一致。</p>
     *
     * <p><b>必须用 LinkedHashMap 而非 Map.of</b>：首要污染物在 IAQI 并列时需要稳定的
     * 比较顺序才能复现入库时 Python {@code max()} 的取舍结果。</p>
     */
    private static final Map<String, double[][]> BREAKPOINTS = new LinkedHashMap<>();

    static {
        double[] iaqiLadder = {0, 50, 100, 150, 200, 300, 400, 500};
        put("pm25", new double[]{0, 35, 75, 115, 150, 250, 350, 500}, iaqiLadder);
        put("pm10", new double[]{0, 50, 150, 250, 350, 420, 500, 600}, iaqiLadder);
        put("so2", new double[]{0, 50, 150, 475, 800, 1600, 2100, 2620}, iaqiLadder);
        put("no2", new double[]{0, 40, 80, 180, 280, 565, 750, 940}, iaqiLadder);
        put("co", new double[]{0, 2, 4, 14, 24, 36, 48, 60}, iaqiLadder);
        // O3 用 8h 滑动均值，国标只定义到 IAQI=300
        BREAKPOINTS.put("o3", new double[][]{
                {0, 100, 160, 215, 265, 800},
                {0, 50, 100, 150, 200, 300}
        });
    }

    private static void put(String pollutant, double[] concentrations, double[] iaqiValues) {
        BREAKPOINTS.put(pollutant, new double[][]{concentrations, iaqiValues});
    }

    /** 字段内部的污染物键 → 对外展示名（如 PM2.5） */
    private static final Map<String, String> DISPLAY_NAMES = new LinkedHashMap<>();

    static {
        DISPLAY_NAMES.put("pm25", "PM2.5");
        DISPLAY_NAMES.put("pm10", "PM10");
        DISPLAY_NAMES.put("so2", "SO2");
        DISPLAY_NAMES.put("no2", "NO2");
        DISPLAY_NAMES.put("co", "CO");
        DISPLAY_NAMES.put("o3", "O3");
    }

    /**
     * 单项污染物 IAQI（分段线性插值，超出最高分段按 500 封顶）。
     *
     * <p>运算顺序与 {@code db/extract_daily.py 的 iaqi()} 完全一致，两边同为 IEEE-754 双精度、
     * 同一运算路径，因此 Java 侧复算结果能与入库数据逐行相同。</p>
     *
     * @param pollutant 污染物键，见 {@link #DISPLAY_NAMES}
     * @param c         浓度值（μg/m³；CO 为 mg/m³）
     * @return IAQI（未取整的浮点值）
     */
    public static double iaqi(String pollutant, double c) {
        double[][] bp = BREAKPOINTS.get(pollutant);
        if (bp == null) {
            throw new IllegalArgumentException("未知污染物: " + pollutant);
        }
        double[] concentrations = bp[0];
        double[] iaqiValues = bp[1];
        if (c <= 0) {
            return 0.0;
        }
        int last = concentrations.length - 1;
        if (c >= concentrations[last]) {
            return 500.0;
        }
        for (int i = 0; i < last; i++) {
            if (c >= concentrations[i] && c < concentrations[i + 1]) {
                return (iaqiValues[i + 1] - iaqiValues[i]) / (concentrations[i + 1] - concentrations[i])
                        * (c - concentrations[i]) + iaqiValues[i];
            }
        }
        return 500.0;
    }

    /**
     * 由六项浓度复算日报 AQI、等级与首要污染物。
     *
     * <p>用途有二：①验证 {@code air_quality} 表中已入库的派生字段是否自洽
     * （集成测试对全部 4,332 行交叉校验）；②若将来接入实时数据，可直接计算而不依赖入库脚本。</p>
     *
     * <p><b>取整说明</b>：AQI 取 {@link RoundingMode#HALF_EVEN}（银行家舍入）。
     * 这不是随意选择——建库数据由 Python {@code round()} 生成，而 Python 的 round 正是 HALF_EVEN；
     * 若此处用 Java 默认的 HALF_UP，落在 .5 上的那部分行会出现 ±1 的偏差，
     * 导致"从库里复算对不上"。详见 {@code docs/task1_verification.md} 的 E1 检查。</p>
     *
     * @param pm25 PM2.5 24h 均值，μg/m³
     * @param pm10 PM10 24h 均值，μg/m³
     * @param so2  SO2 24h 均值，μg/m³
     * @param no2  NO2 24h 均值，μg/m³
     * @param co   CO 24h 均值，mg/m³
     * @param o3   O3 8h 滑动均值最大，μg/m³
     * @return 复算结果
     * @throws IllegalStateException 六项浓度全部缺失（无法计算）
     */
    public static AqiComputeResult computeAqi(Integer pm25, Integer pm10, Integer so2,
                                              Integer no2, BigDecimal co, Integer o3) {
        Map<String, Double> iaqis = new LinkedHashMap<>();
        addIaqi(iaqis, "pm25", pm25);
        addIaqi(iaqis, "pm10", pm10);
        addIaqi(iaqis, "so2", so2);
        addIaqi(iaqis, "no2", no2);
        if (co != null) {
            iaqis.put("co", iaqi("co", co.doubleValue()));
        }
        addIaqi(iaqis, "o3", o3);

        if (iaqis.isEmpty()) {
            throw new IllegalStateException("六项污染物浓度全部缺失，无法计算 AQI");
        }

        double max = Double.NEGATIVE_INFINITY;
        for (Double v : iaqis.values()) {
            max = Math.max(max, v);
        }
        int aqi = roundHalfEven(max);

        // 首要污染物：IAQI > 50 且有超标的项里取最大者；并列时取展示名排序靠后者，
        // 与 Python max((value, key)) 的元组比较语义一致。
        String primary = null;
        String bestKey = null;
        double bestValue = Double.NEGATIVE_INFINITY;
        if (aqi > 50) {
            for (Map.Entry<String, Double> e : iaqis.entrySet()) {
                double v = e.getValue();
                if (v <= 50) {
                    continue;
                }
                boolean better = v > bestValue
                        || (v == bestValue && bestKey != null && e.getKey().compareTo(bestKey) > 0);
                if (better) {
                    bestValue = v;
                    bestKey = e.getKey();
                }
            }
            primary = bestKey == null ? null : DISPLAY_NAMES.get(bestKey);
        }
        return new AqiComputeResult(aqi, levelOf(aqi), levelLabelOf(aqi), primary);
    }

    private static void addIaqi(Map<String, Double> target, String pollutant, Integer concentration) {
        if (concentration != null) {
            target.put(pollutant, iaqi(pollutant, concentration.doubleValue()));
        }
    }

    private static int roundHalfEven(double value) {
        return BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_EVEN).intValue();
    }

    // ==================================================================
    // 私有辅助
    // ==================================================================

    private LocalDate requireMaxDate() {
        LocalDate maxDate = airQualityMapper.findMaxDate();
        if (maxDate == null) {
            throw new IllegalStateException("数据表为空，请检查 db/init.sql 是否已导入");
        }
        return maxDate;
    }

    // ------------------------------------------------------------------
    // A2~A4 辅助
    // ------------------------------------------------------------------

    /**
     * 校验并规范化指标键。
     *
     * <p>白名单来自 {@link #METRICS}，未命中直接 400——
     * 这样指标集合只有一处定义，新增指标只需改静态块。</p>
     */
    private static String requireMetric(String metric) {
        if (metric == null || metric.isBlank()) {
            return METRIC_AQI;
        }
        String key = metric.trim().toLowerCase();
        if (!METRICS.containsKey(key)) {
            throw new IllegalArgumentException(
                    "不支持的指标: " + metric + "，可选 " + String.join("/", METRICS.keySet()));
        }
        return key;
    }

    /**
     * 解析日期区间：缺省值补全 + 边界钳制 + 顺序校验。
     *
     * <p>之所以<b>钳制而非拒绝</b>越界日期：看板是滑块/快捷按钮驱动，
     * 用户极易拖到数据集外，此时返回空结果比报错更合理。
     * 但起止顺序颠倒一定是误操作，必须明确报错。</p>
     */
    private LocalDate[] resolveRange(LocalDate start, LocalDate end) {
        LocalDate minDate = airQualityMapper.findMinDate();
        LocalDate maxDate = requireMaxDate();
        LocalDate resolvedStart = start == null ? minDate : start;
        LocalDate resolvedEnd = end == null ? maxDate : end;
        if (resolvedStart.isBefore(minDate)) {
            resolvedStart = minDate;
        }
        if (resolvedEnd.isAfter(maxDate)) {
            resolvedEnd = maxDate;
        }
        if (resolvedStart.isAfter(resolvedEnd)) {
            throw new IllegalArgumentException("起始日期不能晚于截止日期: " + start + " ~ " + end);
        }
        return new LocalDate[]{resolvedStart, resolvedEnd};
    }

    /** 城市集合规范化：null → 空集合（SQL 里空集合 = 不加 IN 条件） */
    private static List<Integer> normalizeCityIds(List<Integer> cityIds) {
        return cityIds == null ? List.of() : cityIds;
    }

    /** 取某行指定指标的浓度值；CO 保留 1 位小数，其余为整数 */
    private static BigDecimal metricValue(AirQuality row, String metric) {
        return switch (metric) {
            case "aqi" -> toDecimal(row.getAqi());
            case "pm25" -> toDecimal(row.getPm25());
            case "pm10" -> toDecimal(row.getPm10());
            case "so2" -> toDecimal(row.getSo2());
            case "no2" -> toDecimal(row.getNo2());
            case "co" -> row.getCo();
            case "o3" -> toDecimal(row.getO3());
            default -> throw new IllegalArgumentException("不支持的指标: " + metric);
        };
    }

    private static BigDecimal toDecimal(Integer value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /** 明细行 → RecordVO；等级统一取当天 AQI 等级（口径见 dataset.md 11.1 ③） */
    private static RecordVO toRecordVO(AirQuality row, String metric) {
        int aqi = row.getAqi();
        return new RecordVO(
                row.getId(),
                row.getCityId(),
                row.getCityName(),
                row.getMonitorDate(),
                metric,
                metricValue(row, metric),
                METRICS.get(metric).unit(),
                aqi,
                levelOf(aqi),
                levelLabelOf(aqi)
        );
    }

    private static String requireRange(String range) {
        if (range == null || range.isBlank()) {
            return RANGE_30D;
        }
        String normalized = range.trim().toLowerCase();
        if (RANGE_7D.equals(normalized) || RANGE_30D.equals(normalized) || RANGE_12M.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("不支持的区间: " + range + "，可选 " + RANGE_7D + "/" + RANGE_30D + "/" + RANGE_12M);
    }

    /** 按区间推导起始日期（含端点，窗口长度含最新日） */
    private static LocalDate startOf(LocalDate endDate, String range) {
        return switch (range) {
            case RANGE_7D -> endDate.minusDays(6);
            case RANGE_30D -> endDate.minusDays(29);
            case RANGE_12M -> endDate.minusMonths(12).plusDays(1);
            default -> throw new IllegalArgumentException("不支持的区间: " + range);
        };
    }

    // ==================================================================
    // 响应 VO（契约见 docs/dataset.md 第 9 节；嵌套声明以维持"单文件"约束）
    // ==================================================================

    /**
     * 城市列表条目：{@code GET /api/cities}（需求 A1）。
     *
     * <p>字段口径即契约：{@code code} 为城市代码，<b>取现有 city.id（1~12）</b>，
     * 不新增列——种子数据 id 由 {@code init.sql} 固定写入，可复现可重跑。
     * 将来若需对接外部系统，只需改 {@link #getCities()} 里的一行映射。</p>
     *
     * <p>不返回经纬度：city 表中的 longitude/latitude 为地图扩展预留，当前无用，
     * 放进响应只会让前端类型与实际不符。</p>
     */
    public record CityBriefVO(Integer code, String name, String province, String region) {
    }

    /**
     * 趋势序列（F5）：统一时间轴 + 各城市数值序列。
     *
     * @param dates  升序去重的时间轴
     * @param series 每个城市一条与 dates 等长的数值序列，缺测位置为 null
     */
    public record SeriesVO(
            String metric,
            String metricLabel,
            String unit,
            List<LocalDate> dates,
            List<CitySeriesVO> series
    ) {
    }

    /**
     * 单城市的一条趋势序列。
     */
    public record CitySeriesVO(
            Integer code,
            String city,
            List<BigDecimal> values
    ) {
    }

    /**
     * A2 明细行。
     *
     * @param value 当前所选指标的浓度值；该日该指标缺测时为 null
     * @param level 当天 AQI 等级。<b>不随 metric 变化</b>——
     *              否则同一天同一城市在趋势图、排行榜、分布图会得到三个互相矛盾的等级。
     *              口径见 {@code docs/dataset.md} 11.1 ③。
     */
    public record RecordVO(
            Long id,
            Integer code,
            String city,
            @JsonFormat(pattern = "yyyy-MM-dd") LocalDate date,
            String metric,
            BigDecimal value,
            String unit,
            int aqi,
            int level,
            String levelLabel
    ) {
    }

    /**
     * 数据集元信息：日期边界与指标可选项。
     *
     * @param minDate 数据集首日
     * @param maxDate 数据集末日（看板意义上的"今天"）
     */
    public record DatasetMetaVO(
            @JsonFormat(pattern = "yyyy-MM-dd") LocalDate minDate,
            @JsonFormat(pattern = "yyyy-MM-dd") LocalDate maxDate,
            List<MetricMeta> metrics,
            String defaultMetric
    ) {
    }

    /**
     * A3 分组统计行。
     *
     * @param samples     该城市在区间内的总行数
     * @param avg         指标均值，保留 1 位小数；全部缺测时为 null
     * @param goodRatio   优良（level≤2）天数占比，百分比，保留 1 位小数
     * @param levelCounts 1~6 档各自天数，键为等级编号，供 F7 环形图直接使用
     */
    public record StatsVO(
            Integer code,
            String city,
            String metric,
            String unit,
            BigDecimal avg,
            BigDecimal max,
            BigDecimal min,
            BigDecimal goodRatio,
            int goodDays,
            int samples,
            Map<Integer, Integer> levelCounts
    ) {
    }

    // ------------------------------------------------------------------
    // 综合分析 VO
    // ------------------------------------------------------------------

    /**
     * 单城市综合画像：六项浓度均值 + 综合指数 + 排名 + 六项单项质量指数。
     *
     * @param compositeIndex 环境空气质量综合指数（六项单项指数之和），<b>越小越清洁</b>
     * @param rank           综合指数升序排名，1 = 最清洁
     * @param ratios         六项单项质量指数（浓度 / 二级标准限值），供雷达图对比；
     *                       值为 1 表示恰好达到标准限值，&gt;1 表示超标
     */
    public record CityCompositeVO(
            Integer code,
            String city,
            String province,
            String region,
            BigDecimal pm25,
            BigDecimal pm10,
            BigDecimal so2,
            BigDecimal no2,
            BigDecimal co,
            BigDecimal o3,
            BigDecimal aqi,
            BigDecimal compositeIndex,
            BigDecimal goodRatio,
            int goodDays,
            int samples,
            int rank,
            Map<String, BigDecimal> ratios
    ) {
    }

    /** 热力图单元格：城市 × 月份 的 AQI 均值 */
    public record MonthCellVO(Integer code, String city, String month, BigDecimal aqi) {
    }

    /** 月度整体走势（所有选中城市合在一起） */
    public record MonthPointVO(String month, BigDecimal aqi, int samples) {
    }

    /**
     * 分类分组结果：区域（华北/东北/…）与季节（春/夏/秋/冬）共用。
     *
     * @param cityCount      该组覆盖的城市数
     * @param compositeIndex 仅区域有值；季节不计算（季节是时间切片，CI 需要完整六项浓度均值才有意义）
     */
    public record GroupVO(
            String name,
            int cityCount,
            BigDecimal aqi,
            BigDecimal compositeIndex,
            BigDecimal goodRatio,
            int samples
    ) {
    }

    /** 占比分布：首要污染物 / 六档等级共用 */
    public record ShareVO(String name, int days, BigDecimal ratio) {
    }

    /**
     * 综合分析总览（{@code GET /api/air-quality/analysis}）。
     *
     * <p>一次请求返回看板「对比」「分类」所需的全部聚合结果，
     * 避免前端为了画一张图再发 N 个请求。</p>
     */
    public record AnalysisVO(
            String start,
            String end,
            int samples,
            List<CityCompositeVO> cities,
            List<MonthCellVO> monthly,
            List<MonthPointVO> monthlyTrend,
            List<GroupVO> regions,
            List<GroupVO> seasons,
            List<ShareVO> pollutants,
            List<ShareVO> levels
    ) {
    }

    /**
     * A4 单条记录详情：六项浓度 + 派生字段。
     */
    public record RecordDetailVO(
            Long id,
            Integer code,
            String city,
            @JsonFormat(pattern = "yyyy-MM-dd") LocalDate date,
            Integer pm25,
            Integer pm10,
            Integer so2,
            Integer no2,
            BigDecimal co,
            Integer o3,
            int aqi,
            String primaryPollutant,
            int level,
            String levelLabel
    ) {
    }

    /**
     * 分页包装。
     *
     * @param total 命中总行数，前端据此算总页数
     * @param page  当前页码，从 1 起
     * @param size  每页条数
     */
    public record PageResult<T>(long total, int page, int size, List<T> list) {
    }

    /**
     * 概览卡数据：{@code GET /api/aqi/overview}。
     *
     * @param primaryPollutant AQI ≤ 50 时为 null，序列化时被省略
     */
    public record OverviewVO(
            String cityName,
            @JsonFormat(pattern = "yyyy-MM-dd") LocalDate monitorDate,
            int aqi,
            int level,
            String levelLabel,
            String primaryPollutant
    ) {
    }

    /**
     * 趋势图数据点：{@code GET /api/aqi/trend}。
     */
    public record TrendPointVO(
            @JsonFormat(pattern = "yyyy-MM-dd") LocalDate date,
            int aqi,
            Integer pm25,
            Integer pm10,
            Integer o3
    ) {
    }

    /**
     * 排行榜条目：{@code GET /api/aqi/ranking}，按 AQI 升序返回，数组下标即排名。
     */
    public record RankItemVO(
            int cityId,
            String cityName,
            int aqi,
            int level,
            String levelLabel,
            String primaryPollutant
    ) {
    }

    /**
     * {@link #computeAqi} 的结果。
     */
    public record AqiComputeResult(int aqi, int level, String levelLabel, String primaryPollutant) {
    }
}
