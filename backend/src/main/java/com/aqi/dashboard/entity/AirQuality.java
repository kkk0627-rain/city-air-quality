package com.aqi.dashboard.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 空气质量日报实体 —— 对应表 {@code air_quality}（12 城 × 361 天 = 4,332 行）。
 *
 * <p>任务 8 交付物之一。字段与 {@code db/init.sql} 的 air_quality 表逐一对应，
 * 单位口径见 {@code docs/dataset.md} 第 5.2 节（PM2.5/PM10/SO2/NO2/O3 为 μg/m³，CO 为 mg/m³）。</p>
 *
 * <p>三个派生字段（aqi / primary_pollutant / level）由清洗脚本按 HJ 633-2012 计算后入库，
 * Service 层的 {@code computeAqi} 可用存储精度复算校验（两者必须完全一致）。</p>
 */
@Data
public class AirQuality implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 城市 ID，外键 → city.id */
    private Integer cityId;

    /** 监测日期（天粒度，非 DATETIME） */
    private LocalDate monitorDate;

    /** PM2.5 24h 均值，μg/m³ */
    private Integer pm25;

    /** PM10 24h 均值，μg/m³ */
    private Integer pm10;

    /** SO2 24h 均值，μg/m³ */
    private Integer so2;

    /** NO2 24h 均值，μg/m³ */
    private Integer no2;

    /** CO 24h 均值，mg/m³（唯一带小数的指标） */
    private BigDecimal co;

    /** O3 8h 滑动均值当日最大，μg/m³ */
    private Integer o3;

    /** 日报 AQI = max(各污染物 IAQI) */
    private Integer aqi;

    /** 首要污染物：AQI ≤ 50 时为 null（即"无"） */
    private String primaryPollutant;

    /** 等级 1~6：优/良/轻度污染/中度污染/重度污染/严重污染 */
    private Integer level;

    /**
     * 非表字段：排行榜查询 JOIN city 时填充的城市名。
     *
     * <p>MyBatis 的 {@code map-underscore-to-camel-case} 会把 {@code c.city_name}
     * 映射到本属性；其余查询不使用该字段。</p>
     */
    private String cityName;
}
