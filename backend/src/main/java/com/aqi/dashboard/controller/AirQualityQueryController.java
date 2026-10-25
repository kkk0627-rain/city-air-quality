package com.aqi.dashboard.controller;

import com.aqi.dashboard.common.Result;
import com.aqi.dashboard.service.AirQualityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 需求 A2 ~ A4 的查询接口 —— 任务 22 交付物。
 *
 * <p><b>为什么要新开一个控制器</b>：既有 {@code AirQualityController}（路径 {@code /api/aqi/*}）
 * 已通过验收，其契约被既有三个前端组件依赖。新增需求另起 {@code /api/air-quality}
 * 一套路径，两套契约并存、互不干扰，也不必回头修改已验收文件。</p>
 *
 * <pre>
 * GET /api/air-quality?cityIds=1,2&amp;metric=pm25&amp;start=…&amp;end=…&amp;page=1&amp;size=10  (A2)
 * GET /api/air-quality/stats?cityIds=&amp;metric=&amp;start=&amp;end=                    (A3)
 * GET /api/air-quality/{id}                                               (A4)
 * </pre>
 *
 * <p>参数校验尽量下沉到 Service：指标白名单、起止顺序、分页上下限都在
 * {@link AirQualityService} 里处理，非法时抛 {@link IllegalArgumentException}，
 * 由任务 6 的全局异常处理器统一转成 400。这样控制器只负责协议转换。</p>
 *
 * <p>日期格式固定 {@code yyyy-MM-dd}（ISO_LOCAL_DATE），
 * 格式错误由 Spring 抛 {@code MethodArgumentTypeMismatchException} → 400。</p>
 */
@RestController
@RequestMapping("/api/air-quality")
@Tag(name = "查询接口 A2~A4", description = "明细分页查询、分组统计、趋势序列、单条详情")
public class AirQualityQueryController {

    private final AirQualityService airQualityService;

    public AirQualityQueryController(AirQualityService airQualityService) {
        this.airQualityService = airQualityService;
    }

    /**
     * 数据集元信息：日期边界 + 可选项。
     *
     * <p><b>为什么需要这个接口</b>：数据集的"今天"是 2025-12-31，而系统当前日期是 2026 年。
     * 若前端日期控件按系统时间初始化，用户一进页面就落在没有数据的区间上。
     * 这里把真实边界下发给前端，用于设置 {@code min/max} 和默认值。</p>
     *
     * @return { minDate, maxDate, metrics, defaultMetric }
     */
    @Operation(summary = "数据集元信息",
            description = "返回数据集的真实日期边界与可选指标列表。"
                    + "前端用它初始化日期控件的 min/max；数据集的末日是 2025-12-31，不是系统当前日期。")
    @GetMapping("/meta")
    public Result<AirQualityService.DatasetMetaVO> meta() {
        return Result.ok(airQualityService.getDatasetMeta());
    }

    /**
     * A2：按城市 + 指标 + 日期区间查询明细（分页）。
     *
     * @param cityIds 城市编号，逗号分隔（如 {@code 1,2,5}）；省略 = 全部城市
     * @param metric  指标键，见 {@link AirQualityService#METRICS}；缺省 {@code aqi}
     * @param start   起始日期 {@code yyyy-MM-dd}；省略 = 数据集首日
     * @param end     截止日期 {@code yyyy-MM-dd}；省略 = 数据集末日
     * @param page    页码，从 1 起；缺省 1
     * @param size    每页条数；缺省 10，上限 {@link AirQualityService#MAX_PAGE_SIZE}
     * @return 分页明细
     */
    @Operation(summary = "A2 明细分页查询",
            description = "按城市 + 指标 + 日期区间查询日报明细，分页返回。"
                    + "参数名是 cityIds / start / end / page / size（不是 cityCodes / startDate / pageSize）。")
    @GetMapping
    public Result<AirQualityService.PageResult<AirQualityService.RecordVO>> list(
            @Parameter(description = "城市编号，逗号分隔；省略 = 全部城市", example = "1,2,5")
            @RequestParam(value = "cityIds", required = false) List<Integer> cityIds,
            @Parameter(description = "指标键：aqi / pm25 / pm10 / so2 / no2 / co / o3", example = "aqi")
            @RequestParam(value = "metric", defaultValue = AirQualityService.METRIC_AQI) String metric,
            @Parameter(description = "起始日期 yyyy-MM-dd；省略 = 数据集首日", example = "2025-12-01")
            @RequestParam(value = "start", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @Parameter(description = "截止日期 yyyy-MM-dd；省略 = 数据集末日", example = "2025-12-31")
            @RequestParam(value = "end", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @Parameter(description = "页码，从 1 起", example = "1")
            @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(description = "每页条数，缺省 10", example = "10")
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return Result.ok(airQualityService.queryRecords(cityIds, metric, start, end, page, size));
    }

    /**
     * A3：按城市分组统计（均值 / 最大 / 最小 / 优良天数占比）。
     *
     * <p>路径字面量 {@code /stats} 优先于下面的 {@code /{id}} 模式匹配，
     * 因此两者共存不会冲突。</p>
     *
     * @param cityIds 城市编号；省略 = 全部城市
     * @param metric  指标键；缺省 {@code aqi}
     * @param start   起始日期
     * @param end     截止日期
     * @return 每城一行统计，按城市编号升序
     */
    @Operation(summary = "A3 分组统计",
            description = "按城市分组统计均值 / 最大 / 最小 / 优良天数占比 / 各等级天数，"
                    + "供城市对比柱状图与等级分布环形图使用。")
    @GetMapping("/stats")
    public Result<List<AirQualityService.StatsVO>> stats(
            @Parameter(description = "城市编号，逗号分隔；省略 = 全部城市", example = "1,2,3")
            @RequestParam(value = "cityIds", required = false) List<Integer> cityIds,
            @Parameter(description = "指标键", example = "aqi")
            @RequestParam(value = "metric", defaultValue = AirQualityService.METRIC_AQI) String metric,
            @Parameter(description = "起始日期 yyyy-MM-dd", example = "2025-01-01")
            @RequestParam(value = "start", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @Parameter(description = "截止日期 yyyy-MM-dd", example = "2025-12-31")
            @RequestParam(value = "end", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return Result.ok(airQualityService.queryStats(cityIds, metric, start, end));
    }

    /**
     * 多城市趋势序列：统一时间轴 + 各城市一条数值序列（F5 折线图）。
     *
     * <p>筛选逻辑与上面 A2 完全一致，只是返回形状不同：按城市排成整条曲线，
     * 而不是分页明细。缺失日期在序列中留 null，前端断线显示即可。</p>
     *
     * @param cityIds 城市编号，逗号分隔；省略 = 全部城市
     * @param metric  指标键；缺省 {@code aqi}
     * @param start   起始日期
     * @param end     截止日期
     * @return { metric, metricLabel, unit, dates, series }
     */
    @Operation(summary = "多城市趋势序列（配套 A3）",
            description = "统一时间轴 + 每城一条数值序列，供折线图使用；"
                    + "缺失日期在序列中留 null，前端断线显示。")
    @GetMapping("/series")
    public Result<AirQualityService.SeriesVO> series(
            @Parameter(description = "城市编号，逗号分隔；省略 = 全部城市", example = "1,5,9")
            @RequestParam(value = "cityIds", required = false) List<Integer> cityIds,
            @Parameter(description = "指标键", example = "aqi")
            @RequestParam(value = "metric", defaultValue = AirQualityService.METRIC_AQI) String metric,
            @Parameter(description = "起始日期 yyyy-MM-dd", example = "2025-12-01")
            @RequestParam(value = "start", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @Parameter(description = "截止日期 yyyy-MM-dd", example = "2025-12-31")
            @RequestParam(value = "end", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return Result.ok(airQualityService.getSeries(cityIds, metric, start, end));
    }

    /**
     * A4：按主键查询单条日报详情。
     *
     * @param id 记录主键
     * @return 详情；<b>不存在时返回 HTTP 404</b> 与标准错误体
     */
    @Operation(summary = "A4 单条记录详情",
            description = "按主键查询某城市某天的完整日报（六项污染物 + AQI + 等级）；"
                    + "不存在时返回 HTTP 404。")
    @GetMapping("/{id}")
    public ResponseEntity<Result<AirQualityService.RecordDetailVO>> detail(
            @Parameter(description = "记录主键，取自 A2 明细列表的 id", required = true, example = "4321")
            @PathVariable("id") long id) {
        AirQualityService.RecordDetailVO vo = airQualityService.getRecordDetail(id);
        if (vo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Result.notFound("记录不存在: id=" + id));
        }
        return ResponseEntity.ok(Result.ok(vo));
    }
}
