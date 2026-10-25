package com.aqi.dashboard.controller;

import com.aqi.dashboard.common.Result;
import com.aqi.dashboard.service.AirQualityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 空气质量看板 REST 接口 —— 任务 12 交付物。
 *
 * <p>四个只读接口，契约严格对齐 {@code docs/dataset.md} 第 9 节：</p>
 *
 * <pre>
 * GET /api/cities                          -> [{ id, cityName, province, region }]
 * GET /api/aqi/overview?cityId=1           -> { cityName, monitorDate, aqi, level, levelLabel, primaryPollutant }
 * GET /api/aqi/trend?cityId=1&amp;range=30d    -> [{ date, aqi, pm25, pm10, o3 }]
 * GET /api/aqi/ranking                     -> [{ cityId, cityName, aqi, level, levelLabel, primaryPollutant }]
 * </pre>
 *
 * <p>全部返回 {@link Result} 包装。异常由任务 6 的 {@code GlobalExceptionHandler} 兜底：
 * 缺参数 / 参数类型错误 / range 非法 → 400，接口路径不存在 → 500 之外不会再有空白页。</p>
 *
 * <p><b>城市不存在的处理</b>：Service 返回 null 时由本类显式输出 404，
 * 保证 HTTP status 与 body 中的 {@code code} 一致（这是资料一致性约束，
 * 也避免为了多带一个 {@code BusinessException} 类而破坏"一个任务只改一个文件"）。</p>
 */
@RestController
@RequestMapping("/api")
@Tag(name = "看板基础接口", description = "城市列表、概览卡、趋势图、排行榜（A1 及配套视图接口）")
public class AirQualityController {

    private final AirQualityService airQualityService;

    public AirQualityController(AirQualityService airQualityService) {
        this.airQualityService = airQualityService;
    }

    /**
     * 城市列表。
     *
     * @return 12 个城市
     */
    @Operation(summary = "A1 城市列表",
            description = "返回全部 12 个城市的编号、名称、省份与区域，供前端城市多选项初始化。")
    @GetMapping("/cities")
    public Result<List<AirQualityService.CityBriefVO>> cities() {
        return Result.ok(airQualityService.getCities());
    }

    /**
     * 概览卡：指定城市的最新一日快照。
     *
     * @param cityId 城市编号（必填）
     * @return 快照；城市不存在时 HTTP 404
     */
    @Operation(summary = "城市概览卡（焦点城市）",
            description = "指定城市在最新监测日的 AQI 快照，含等级与首要污染物；城市不存在时返回 HTTP 404。")
    @GetMapping("/aqi/overview")
    public ResponseEntity<Result<AirQualityService.OverviewVO>> overview(
            @Parameter(description = "城市编号，取自 /api/cities 的 code", required = true, example = "1")
            @RequestParam("cityId") int cityId) {
        AirQualityService.OverviewVO vo = airQualityService.getOverview(cityId);
        return vo == null ? cityNotFound(cityId) : ResponseEntity.ok(Result.ok(vo));
    }

    /**
     * 趋势图：指定城市的逐日 AQI。
     *
     * @param cityId 城市编号（必填）
     * @param range  时间窗口，{@code 7d} / {@code 30d} / {@code 12m}，默认 {@code 30d}
     * @return 数据点列表（按日期升序）；城市不存在或 range 非法时返回标准错误体
     */
    @Operation(summary = "单城市趋势（旧版）",
            description = "指定城市逐日 AQI。range=7d/30d/12m；城市不存在或 range 非法时返回 HTTP 404。")
    @GetMapping("/aqi/trend")
    public ResponseEntity<Result<List<AirQualityService.TrendPointVO>>> trend(
            @Parameter(description = "城市编号", required = true, example = "1")
            @RequestParam("cityId") int cityId,
            @Parameter(description = "时间窗口：7d / 30d / 12m", example = "30d")
            @RequestParam(value = "range", defaultValue = AirQualityService.RANGE_30D) String range) {
        List<AirQualityService.TrendPointVO> points = airQualityService.getTrend(cityId, range);
        return points == null ? cityNotFound(cityId) : ResponseEntity.ok(Result.ok(points));
    }

    /**
     * 排行榜：全部城市在最新监测日的 AQI 快照（升序）。
     *
     * @return 12 行榜单
     */
    @Operation(summary = "城市排行榜（旧版）",
            description = "全部城市在最新监测日的 AQI 快照，按 AQI 升序。")
    @GetMapping("/aqi/ranking")
    public Result<List<AirQualityService.RankItemVO>> ranking() {
        return Result.ok(airQualityService.getRanking());
    }

    private static <T> ResponseEntity<Result<T>> cityNotFound(int cityId) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.notFound("城市不存在: id=" + cityId));
    }
}
