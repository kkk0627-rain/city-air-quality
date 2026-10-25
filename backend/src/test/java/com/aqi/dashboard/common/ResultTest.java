package com.aqi.dashboard.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务 5 验证：{@link Result} 统一返回体的结构与工厂方法。
 *
 * <p>纯单元测试，不加载 Spring 上下文、不连数据库，可独立运行。</p>
 */
@DisplayName("任务5 - 统一返回体 Result")
class ResultTest {

    @Test
    @DisplayName("ok(data) 应返回 code=0、msg=ok、data 为传入值")
    void ok_withData() {
        Result<String> r = Result.ok("hello");

        assertEquals(0, r.getCode(), "成功时 code 必须为 0");
        assertEquals("ok", r.getMsg());
        assertEquals("hello", r.getData());
        assertTrue(r.isSuccess());
    }

    @Test
    @DisplayName("ok() 无参时应返回 code=0 且 data 为 null")
    void ok_noData() {
        Result<Void> r = Result.ok();

        assertEquals(0, r.getCode());
        assertNull(r.getData());
        assertTrue(r.isSuccess());
    }

    @Test
    @DisplayName("ok(data, msg) 应支持自定义成功提示")
    void ok_customMsg() {
        Result<List<Integer>> r = Result.ok(List.of(1, 2, 3), "查询成功");

        assertEquals(0, r.getCode());
        assertEquals("查询成功", r.getMsg());
        assertEquals(3, r.getData().size());
    }

    @Test
    @DisplayName("fail(msg) 默认应为 500")
    void fail_defaultCode() {
        Result<Void> r = Result.fail("服务器开小差了");

        assertEquals(500, r.getCode());
        assertEquals("服务器开小差了", r.getMsg());
        assertNull(r.getData());
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("fail(code, msg) 应支持自定义状态码")
    void fail_customCode() {
        Result<Void> r = Result.fail(Result.CODE_NOT_FOUND, "城市不存在");

        assertEquals(404, r.getCode());
        assertEquals("城市不存在", r.getMsg());
    }

    @Test
    @DisplayName("badRequest / notFound 快捷方法的码值应固定")
    void fail_shortcuts() {
        assertEquals(400, Result.badRequest("参数错误").getCode());
        assertEquals(404, Result.notFound("未找到").getCode());
    }

    @Test
    @DisplayName("常量值应与 HTTP 语义对齐，避免前端分支写死出错")
    void constants() {
        assertEquals(0, Result.CODE_OK);
        assertEquals(400, Result.CODE_BAD_REQUEST);
        assertEquals(404, Result.CODE_NOT_FOUND);
        assertEquals(500, Result.CODE_ERROR);
    }

    @Test
    @DisplayName("data 为 null 时序列化不应抛异常（Jackson non_null 生效的前提）")
    void nullDataSafe() {
        Result<String> r = Result.fail("空");

        assertDoesNotThrow(() -> assertNotNull(r.toString()));
        assertTrue(r.toString().contains("code=500"));
    }
}
