package com.aqi.dashboard.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 城市实体 —— 对应表 {@code city}（12 行）。
 *
 * <p>任务 8 交付物之一。字段与 {@code db/init.sql} 的 city 表逐一对应：</p>
 *
 * <table>
 *   <caption>列名映射（依赖 application.yml 的 map-underscore-to-camel-case）</caption>
 *   <tr><th>列名</th><th>属性</th></tr>
 *   <tr><td>id</td><td>id</td></tr>
 *   <tr><td>city_name</td><td>cityName</td></tr>
 *   <tr><td>province</td><td>province</td></tr>
 *   <tr><td>region</td><td>region</td></tr>
 *   <tr><td>longitude</td><td>longitude</td></tr>
 *   <tr><td>latitude</td><td>latitude</td></tr>
 * </table>
 *
 * <p>longitude / latitude 为地图扩展预留，当前版本接口不返回。</p>
 */
@Data
public class City implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 城市编号 1~12，对应 dataset.md 第 2 节城市清单 */
    private Integer id;

    /** 城市名，如"北京" */
    private String cityName;

    /** 省份，如"北京市" */
    private String province;

    /** 所属地理区：华北/东北/中原/华东/华南/西南/西北 */
    private String region;

    /** 经度（预留） */
    private BigDecimal longitude;

    /** 纬度（预留） */
    private BigDecimal latitude;
}
