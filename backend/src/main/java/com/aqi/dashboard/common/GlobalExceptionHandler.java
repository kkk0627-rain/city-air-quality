package com.aqi.dashboard.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：把各层抛出的异常统一转成 {@link Result} 结构。
 *
 * <p>依赖任务 5 的 {@code Result}。设计要点：</p>
 * <ol>
 *   <li>返回 HTTP status 与 body 中的 {@code code} 保持一致，前端二选一即可判断；</li>
 *   <li>参数类异常（400）只记 debug/warn，不打堆栈——用户输错参数不是系统故障；</li>
 *   <li>兜底 {@code Exception}（500）必须记 error + 堆栈，否则线上排障无从下手；</li>
 *   <li>响应体 message 不暴露内部异常类名与 SQL 细节，避免信息泄露。</li>
 * </ol>
 *
 * <p><b>关于"业务异常"</b>：为遵守"一个任务只改一个文件"的约束，本次不新增
 * {@code BusinessException} 类。业务校验失败统一抛 {@link IllegalArgumentException}
 * （400）或 {@link IllegalStateException}（500），后续任务 11 若需区分业务码，
 * 再在本类中追加一个 {@code @ExceptionHandler} 分支即可。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================================================================
    // 400 参数错误
    // ==================================================================

    /** @RequestBody + @Valid 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, Result.CODE_BAD_REQUEST, defaultMsg(msg, "参数校验失败"));
    }

    /** 表单/查询参数绑定校验失败 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException e) {
        FieldError first = e.getFieldError();
        String msg = first == null ? "参数绑定失败" : first.getField() + " " + first.getDefaultMessage();
        log.warn("参数绑定失败: {}", msg);
        return build(HttpStatus.BAD_REQUEST, Result.CODE_BAD_REQUEST, msg);
    }

    /** @Validated 标注在类上时的单参数校验失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数约束校验失败: {}", msg);
        return build(HttpStatus.BAD_REQUEST, Result.CODE_BAD_REQUEST, defaultMsg(msg, "参数校验失败"));
    }

    /** 缺少必需的请求参数，如 /api/aqi/trend 未带 cityId */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        String msg = "缺少必需参数: " + e.getParameterName();
        log.warn(msg);
        return build(HttpStatus.BAD_REQUEST, Result.CODE_BAD_REQUEST, msg);
    }

    /** 参数类型不匹配，如 cityId=abc */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String msg = "参数类型错误: " + e.getName();
        log.warn("{} (传入值: {})", msg, e.getValue());
        return build(HttpStatus.BAD_REQUEST, Result.CODE_BAD_REQUEST, msg);
    }

    /** 请求体为空或 JSON 格式错误 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, Result.CODE_BAD_REQUEST, "请求体格式错误");
    }

    /** 业务校验失败：Service/Controller 主动抛出的参数非法 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("业务参数非法: {}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, Result.CODE_BAD_REQUEST,
                defaultMsg(e.getMessage(), "参数不合法"));
    }

    /** 请求方法不支持，如用 POST 访问只支持 GET 的接口 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        String msg = "不支持的请求方法: " + e.getMethod();
        log.warn(msg);
        return build(HttpStatus.BAD_REQUEST, Result.CODE_BAD_REQUEST, msg);
    }

    // ==================================================================
    // 404 资源不存在
    // ==================================================================

    /** 接口路径不存在（需在 application.yml 开启 throw-exception-if-no-handler-found） */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<Void>> handleNoHandlerFound(NoHandlerFoundException e, HttpServletRequest req) {
        String msg = "接口不存在: " + e.getRequestURL();
        log.warn("{} (method={})", msg, req.getMethod());
        return build(HttpStatus.NOT_FOUND, Result.CODE_NOT_FOUND, msg);
    }

    /** Spring Boot 3.2 对静态资源未命中的默认异常，一并归为 404 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("静态资源不存在: {}", e.getResourcePath());
        return build(HttpStatus.NOT_FOUND, Result.CODE_NOT_FOUND, "资源不存在: " + e.getResourcePath());
    }

    // ==================================================================
    // 500 服务端异常
    // ==================================================================

    /** 状态非法（如数据源未就绪、配置缺失） */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<Void>> handleIllegalState(IllegalStateException e) {
        log.error("系统状态异常", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, Result.CODE_ERROR, "系统状态异常，请稍后重试");
    }

    /** 兜底：任何未预料到的异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e, HttpServletRequest req) {
        log.error("未处理异常: {} {}", req.getMethod(), req.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, Result.CODE_ERROR, "服务器内部错误，请稍后重试");
    }

    // ==================================================================

    private static ResponseEntity<Result<Void>> build(HttpStatus status, int code, String msg) {
        // Result 只暴露 getter（不可变语义），用全参构造器构造
        Result<Void> body = new Result<>(code, msg, null);
        return ResponseEntity.status(status).body(body);
    }

    private static String defaultMsg(String candidate, String fallback) {
        return (candidate == null || candidate.isBlank()) ? fallback : candidate;
    }
}
