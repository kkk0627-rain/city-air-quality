package com.aqi.dashboard.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一响应体：所有 Controller 接口的返回结构。
 *
 * <p>契约（与 {@code docs/dataset.md} 第 9 节一致）：</p>
 * <pre>
 * { "code": 0, "msg": "ok", "data": ... }
 * </pre>
 *
 * <p>状态码约定：{@code 0} 表示成功；非 0 与 HTTP 语义对齐，
 * 便于前端按 {@code code} 分支而不必解析 HTTP status。</p>
 *
 * <ul>
 *   <li>0   - 成功</li>
 *   <li>400 - 参数校验失败 / 业务参数非法</li>
 *   <li>404 - 资源不存在（城市 ID 不存在、接口路径错误）</li>
 *   <li>500 - 服务端异常（兜底）</li>
 * </ul>
 *
 * @param <T> 业务数据类型
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "Result", description = "统一响应体：{ code, msg, data }")
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成功 */
    public static final int CODE_OK = 0;
    /** 参数错误 */
    public static final int CODE_BAD_REQUEST = 400;
    /** 资源不存在 */
    public static final int CODE_NOT_FOUND = 404;
    /** 服务端异常 */
    public static final int CODE_ERROR = 500;

    /** 状态码 */
    @Schema(description = "状态码，0 表示成功；非 0 与 HTTP 语义对齐",
            example = "0", allowableValues = {"0", "400", "404", "500"})
    private int code;

    /** 提示信息 */
    @Schema(description = "提示信息；成功时固定为 ok，失败时为错误原因", example = "ok")
    private String msg;

    /** 业务数据，失败时为 null */
    @Schema(description = "业务数据；失败时为 null")
    private T data;

    // ------------------------------------------------------------------
    // 成功
    // ------------------------------------------------------------------

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = CODE_OK;
        r.msg = "ok";
        r.data = data;
        return r;
    }

    public static <T> Result<T> ok(T data, String msg) {
        Result<T> r = new Result<>();
        r.code = CODE_OK;
        r.msg = msg;
        r.data = data;
        return r;
    }

    // ------------------------------------------------------------------
    // 失败
    // ------------------------------------------------------------------

    public static <T> Result<T> fail(String msg) {
        return fail(CODE_ERROR, msg);
    }

    public static <T> Result<T> fail(int code, String msg) {
        Result<T> r = new Result<>();
        r.code = code;
        r.msg = msg;
        r.data = null;
        return r;
    }

    public static <T> Result<T> badRequest(String msg) {
        return fail(CODE_BAD_REQUEST, msg);
    }

    public static <T> Result<T> notFound(String msg) {
        return fail(CODE_NOT_FOUND, msg);
    }

    // ------------------------------------------------------------------

    /**
     * 是否成功。
     *
     * <p>仅供 Java 侧（Service 判空、测试断言）使用，<b>不参与 JSON 序列化</b>：
     * Lombok 会为 {@code isSuccess()} 生成名为 {@code success} 的 JSON 属性，
     * 与 {@code docs/dataset.md} 第 9 节约定的 {@code {code, msg, data}} 三字段不符，
     * 故显式排除，前端统一按 {@code code === 0} 判断成功。</p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isSuccess() {
        return this.code == CODE_OK;
    }

    @Override
    public String toString() {
        return "Result{code=" + code + ", msg='" + msg + "', data=" + data + '}';
    }
}
