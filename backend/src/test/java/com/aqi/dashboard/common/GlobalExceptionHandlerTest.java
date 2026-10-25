package com.aqi.dashboard.common;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 6 验证：{@link GlobalExceptionHandler} 是否把各类异常统一转成 Result 结构。
 *
 * <p>用 MockMvc 独立装配（standaloneSetup）挂载一个探针 Controller，
 * 不启动 Tomcat、不连数据库，因此可独立验证。</p>
 *
 * <p>依赖任务 5 的 {@code Result}。</p>
 */
@DisplayName("任务6 - 全局异常处理 GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("正常请求应返回 code=0，且不被异常处理拦截")
    void normalRequest() throws Exception {
        mockMvc.perform(get("/probe/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("ok"));
    }

    @Test
    @DisplayName("业务参数非法 -> HTTP 400 + code=400，并回显业务原因")
    void illegalArgument() throws Exception {
        mockMvc.perform(get("/probe/illegal"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("cityId 必须是正整数"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("缺少必需参数 -> 400，提示中应包含参数名")
    void missingParam() throws Exception {
        mockMvc.perform(get("/probe/missing"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(Matchers.containsString("cityId")));
    }

    @Test
    @DisplayName("参数类型不匹配 -> 400，不抛裸异常")
    void typeMismatch() throws Exception {
        mockMvc.perform(get("/probe/type").param("cityId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(Matchers.containsString("cityId")));
    }

    @Test
    @DisplayName("系统状态异常 -> 500，且不泄露内部异常细节")
    void illegalState() throws Exception {
        mockMvc.perform(get("/probe/state"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value(Matchers.not(
                        Matchers.containsString("数据源未就绪"))));
    }

    @Test
    @DisplayName("未知异常兜底 -> 500，响应体不含原始异常信息（防信息泄露）")
    void unexpectedException() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("服务器内部错误，请稍后重试"))
                .andExpect(jsonPath("$.msg").value(Matchers.not(
                        Matchers.containsString("模拟空指针"))));
    }

    /**
     * 探针 Controller：仅用于触发各类异常，不参与正式接口。
     * 放在测试内部，避免为验证而新增生产代码文件。
     */
    @RestController
    static class ProbeController {

        @GetMapping("/probe/ok")
        public Result<String> ok() {
            return Result.ok("ok");
        }

        @GetMapping("/probe/illegal")
        public Result<String> illegal() {
            throw new IllegalArgumentException("cityId 必须是正整数");
        }

        @GetMapping("/probe/state")
        public Result<String> state() {
            throw new IllegalStateException("数据源未就绪");
        }

        @GetMapping("/probe/boom")
        public Result<String> boom() {
            throw new RuntimeException("模拟空指针");
        }

        @GetMapping("/probe/missing")
        public Result<String> missing(@RequestParam Long cityId) {
            return Result.ok(String.valueOf(cityId));
        }

        @GetMapping("/probe/type")
        public Result<String> type(@RequestParam Integer cityId) {
            return Result.ok(String.valueOf(cityId));
        }
    }
}
