package com.aqi.dashboard.mapper;

import com.aqi.dashboard.entity.AirQuality;
import com.aqi.dashboard.entity.City;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 空气质量看板的数据访问接口 —— 任务 9 交付物。
 *
 * <p>SQL 实现在 {@code resources/mapper/AirQualityMapper.xml}（任务 10），
 * 两者通过 接口全限定名 = XML namespace + statement id 绑定。
 * 因此本文件可先于 XML 编译通过，但只有两者齐备才能运行。</p>
 *
 * <p>五个方法分别服务三块界面（映射关系见 {@code docs/dataset.md} 第 7 节）：</p>
 * <ul>
 *   <li>{@link #findAllCities()} —— 城市下拉框</li>
 *   <li>{@link #findMaxDate()} + {@link #findLatestByCity(int)} —— 概览卡</li>
 *   <li>{@link #findMaxDate()} + {@link #findTrend(int, LocalDate, LocalDate)} —— 趋势图</li>
 *   <li>{@link #findMaxDate()} + {@link #findRankingByDate(LocalDate)} —— 排行榜</li>
 * </ul>
 *
 * <p>{@code @Mapper} 让 MyBatis 自动装配扫描到本接口（无需改动启动类，
 * 保持"一个任务只改一个文件"的约束）。</p>
 */
@Mapper
public interface AirQualityMapper {

    /**
     * 城市列表，按主键升序（城市下拉框的选项顺序）。
     *
     * @return 12 行城市记录
     */
    List<City> findAllCities();

    /**
     * 按主键查询单个城市。
     *
     * @param id 城市编号
     * @return 城市记录；不存在时返回 null
     */
    City findCityById(@Param("id") int id);

    /**
     * 数据集的"当前日期"：全表最大监测日。
     *
     * <p>看板是历史数据集的演示，"当前 AQI"统一定义为该日期的快照，
     * 而非系统时间——否则 2026 年打开页面会因无数据而空白。</p>
     *
     * @return 最新监测日期（本数据集为 2025-12-31）；空表时返回 null
     */
    LocalDate findMaxDate();

    /**
     * 某城市在最新监测日的快照（概览卡）。
     *
     * <p>走唯一键 {@code uk_city_date} 单行定位。</p>
     *
     * @param cityId 城市编号
     * @return 该城市最新一行日报；城市无数据时返回 null
     */
    AirQuality findLatestByCity(@Param("cityId") int cityId);

    /**
     * 某城市在指定日期区间内的逐日记录（趋势折线图）。
     *
     * <p>走 {@code uk_city_date} 的范围扫描，天粒度下无需任何聚合。</p>
     *
     * @param cityId    城市编号
     * @param startDate 起始日期（含）
     * @param endDate   截止日期（含）
     * @return 按日期升序的记录列表
     */
    List<AirQuality> findTrend(@Param("cityId") int cityId,
                               @Param("startDate") LocalDate startDate,
                               @Param("endDate") LocalDate endDate);

    /**
     * 全城市在同一日期的横向快照（排行榜），按 AQI 升序。
     *
     * <p>走 {@code idx_date} 并 JOIN city 取得城市名。同一时刻才能横向比较，
     * 所以这里必须传具体日期而非取各城各自的最新值。</p>
     *
     * @param monitorDate 快照日期
     * @return 12 行排行榜记录，{@code cityName} 已填充
     */
    List<AirQuality> findRankingByDate(@Param("monitorDate") LocalDate monitorDate);

    // ==================================================================
    // 需求 A2~A4 新增（契约见 docs/dataset.md 第 11 节）
    //
    // 设计取舍：统计走「SQL 取明细 + Java 侧聚合」，而不是在 XML 里
    // 用 GROUP BY + 动态列名。原因：
    //   1. 上限仅 4,332 行（12 城 × 365 天），内存聚合开销可忽略；
    //   2. 避免在 XML 里拼 ${metric} 列名——即便有白名单校验，也是
    //      安全隐患与代码坏味道；
    //   3. 优良率口径（level <= 2）必须与 Service 的 levelOf() 单一实现，
    //      放 Java 侧不会分裂成 SQL / Java 两份逻辑；
    //   4. 若将来升级到小时粒度（105,120 行），改为 SQL 聚合即可，
    //      方法签名不变，调用方无感。
    // ==================================================================

    /**
     * 数据集起始日：全表最小监测日。
     *
     * <p>与 {@link #findMaxDate()} 成对使用，作为日期筛选的合法边界，
     * 供前端日期控件的 {@code min/max} 与后端参数钳制共用。</p>
     *
     * @return 最早监测日期（本数据集为 2025-01-01）；空表时返回 null
     */
    LocalDate findMinDate();

    /**
     * 按城市集合 + 日期区间查询明细（分页，供 F8 详情表格）。
     *
     * <p>{@code cityIds} 为 null 或空集合时表示不过滤城市。
     * 排序按日期倒序（最新在前），日期相同时按城市编号升序，保证分页结果稳定。</p>
     *
     * @param cityIds   城市编号集合；null/空 = 全部
     * @param startDate 起始日期（含），null 表示不限
     * @param endDate   截止日期（含），null 表示不限
     * @param offset    分页偏移
     * @param size      每页条数
     * @return 当前页记录列表，{@code cityName} 已填充
     */
    List<AirQuality> findByConditions(@Param("cityIds") List<Integer> cityIds,
                                      @Param("startDate") LocalDate startDate,
                                      @Param("endDate") LocalDate endDate,
                                      @Param("offset") long offset,
                                      @Param("size") int size);

    /**
     * 与 {@link #findByConditions} 同条件的总行数（分页控件需要）。
     *
     * @param cityIds   城市编号集合；null/空 = 全部
     * @param startDate 起始日期（含），null 表示不限
     * @param endDate   截止日期（含），null 表示不限
     * @return 命中行数
     */
    long countByConditions(@Param("cityIds") List<Integer> cityIds,
                           @Param("startDate") LocalDate startDate,
                           @Param("endDate") LocalDate endDate);

    /**
     * 按城市集合 + 日期区间取出全部明细，供 Service 侧做分组统计（A3 / F6 / F7）。
     *
     * <p>注意：本方法<b>不做分页</b>，返回区间内所有行。当前数据集上限 4,332 行，
     * 可安全一次性载入；若将来扩到小时粒度需改为 SQL 聚合。</p>
     *
     * @param cityIds   城市编号集合；null/空 = 全部
     * @param startDate 起始日期（含）
     * @param endDate   截止日期（含）
     * @return 按城市编号、日期升序的记录列表
     */
    List<AirQuality> findStatsByConditions(@Param("cityIds") List<Integer> cityIds,
                                           @Param("startDate") LocalDate startDate,
                                           @Param("endDate") LocalDate endDate);

    /**
     * 按主键查询单条日报（A4 详情）。
     *
     * @param id 记录主键
     * @return 记录；不存在时返回 null
     */
    AirQuality findRecordById(@Param("id") long id);
}
