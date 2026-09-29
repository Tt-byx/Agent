package com.scenic.ai.exception;

/**
 * Wave 0 · 业务异常：携带 HTTP 语义的状态码，由 {@link GlobalExceptionHandler} 统一转成 JSON。
 *
 * 引入它的直接原因是「会话归属校验」需要在 Service 层拒绝请求，
 * 而 Service 返回的是业务对象、无法直接表达 403。
 */
public class ApiException extends RuntimeException {

    private final int code;

    public ApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }

    public static ApiException tooManyRequests(String message) {
        return new ApiException(429, message);
    }

    /** Wave 4 · 幂等冲突：同一请求正在处理中 */
    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }
}
