package com.scenic.ai.exception;

import com.scenic.ai.dto.Result;
import com.scenic.ai.exception.RedisShortCircuitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Wave 0 · 全局异常处理。
 *
 * 统一出口的价值：客户端拿到的永远是 {@link Result} 结构（而不是偶尔冒出 HTML 错误页），
 * 且 5xx 的堆栈只进日志、不出响应体（避免泄漏内部实现）。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** 业务异常：按声明的状态码返回 */
    /**
     * Redis 短路 —— 服务降级。
     *
     * <p>和普通业务异常分开处理：短路意味着「依赖挂了」而不是「请求不合法」，
     * 应该返回 503（Service Unavailable）让客户端知道可重试，
     * 而不是 200 + 提示语（那样监控看不到降级，重试策略也不对）。
     */
    @ExceptionHandler(RedisShortCircuitException.class)
    public ResponseEntity<Result<Void>> handleShortCircuit(RedisShortCircuitException e) {
        log.warn("[DEGRADE] Redis 短路中，返回 503：{}", e.getMessage());
        return ResponseEntity.status(503).body(Result.error(503, e.getMessage()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Result<Void>> handleApiException(ApiException e) {
        log.info("[API] {} -> {}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getCode()).body(Result.error(e.getCode(), e.getMessage()));
    }

    /** 参数校验失败（@Valid）：把第一条校验信息回给客户端 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("请求参数不合法");
        return ResponseEntity.badRequest().body(Result.error(400, message));
    }

    /** 请求体无法解析（JSON 格式错误 / 字段类型不匹配）：归为客户端错误 400，而不是 500 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.info("[API] 请求体解析失败: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Result.error(400, "请求体格式不正确"));
    }

    /** 兜底：日志留全栈，响应只给通用提示 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(500).body(Result.error(500, "服务内部错误，请稍后重试"));
    }
}
