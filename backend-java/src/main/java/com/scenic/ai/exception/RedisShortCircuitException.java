package com.scenic.ai.exception;

/**
 * Redis 短路异常。
 *
 * <p>触发时机：一次 Redis 调用失败后，短路窗口内（默认 10 秒）的**后续**调用
 * 直接抛这个异常，完全不触碰网络。
 *
 * <p>为什么不用 {@link ApiException}：
 * 项目里有大量 {@code catch (Exception e)} 的「失败就降级」代码（限流放行、
 * 记忆降级、缓存降级…）。如果短路异常和它们混用，会被误吞成静默降级。
 * 专门的类型让「这是短路，必须冒泡」这件事显式可见。
 *
 * <p>最终由 {@link com.scenic.ai.exception.GlobalExceptionHandler} 映射成
 * HTTP 503 —— 让用户明确知道服务正在降级，而不是「提问了半天没反应」。
 */
public class RedisShortCircuitException extends RuntimeException {

    private final long recoverAfterMs;

    public RedisShortCircuitException(String message, long recoverAfterMs) {
        super(message);
        this.recoverAfterMs = recoverAfterMs;
    }

    /** 距离短路窗口结束还有多久（毫秒），用于提示语 */
    public long getRecoverAfterMs() {
        return recoverAfterMs;
    }

    /**
     * 让短路异常冒泡。
     *
     * <p>放在各个 {@code catch (Exception e)} 块的最前面：
     * <pre>
     * } catch (Exception e) {
     *     Exceptions.rethrowIfShortCircuit(e);   // ← 必须放在第一位
     *     log.warn("Redis 调用失败，降级处理: {}", e.getMessage());
     * }
     * </pre>
     * 顺序很重要：写在最后会被「降级」逻辑覆盖掉。
     */
    public static void rethrowIf(Exception e) {
        if (e instanceof RedisShortCircuitException) {
            throw (RedisShortCircuitException) e;
        }
    }
}
