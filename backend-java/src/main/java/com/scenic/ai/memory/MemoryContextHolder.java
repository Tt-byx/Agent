package com.scenic.ai.memory;

/**
 * 长期记忆的归属键传递。
 *
 * AgentLoop 在每轮开始时把 memoryKey 放进来，工具执行时取用。
 * 用 ThreadLocal 而不是让工具方法多收一个参数，是为了保持 @Tool 方法签名干净
 * （工具的参数 schema 是暴露给模型的，不应该混入框架内部标识）。
 *
 * 注意：工具执行是同步的（在调用线程上），所以 ThreadLocal 是安全的。
 */
public final class MemoryContextHolder {

    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    private MemoryContextHolder() {
    }

    public static void set(String memoryKey) {
        CONTEXT.set(memoryKey);
    }

    public static String current() {
        String key = CONTEXT.get();
        return key == null ? "" : key;
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
