package tech.qunxu.module.document.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 同键并发合流：同一份内容被同时提交多次（批量导入、重复点击）时，只解析一次，其余线程等待结果。
 * <p>
 * 这是「唯一解析源」在进程内的部分；跨进程/跨实例的部分由 {@link ConversionCacheStore} 承担。
 * 等待超时后不抛业务异常，而是自己算一遍（宁可多解析一次，也不要让请求卡死）。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class SingleFlight {

    private static final Logger log = LoggerFactory.getLogger(SingleFlight.class);

    private final Map<String, CompletableFuture<Object>> flights = new ConcurrentHashMap<>();
    private final long waitTimeoutMillis;

    public SingleFlight() {
        this(TimeUnit.MINUTES.toMillis(10));
    }

    public SingleFlight(long waitTimeoutMillis) {
        this.waitTimeoutMillis = waitTimeoutMillis <= 0 ? TimeUnit.MINUTES.toMillis(10) : waitTimeoutMillis;
    }

    /**
     * 以 {@code key} 合流执行 {@code loader}：首个调用者真正执行，后来的调用者等待其结果。
     *
     * @param key    合流键（用 {@link ConversionKey#canonical()}）
     * @param loader 实际执行体
     * @return 结果（可能是自己算的，也可能是同键首个调用者算的）
     * @throws Exception loader 抛出的原始异常（对等待者同样抛出）
     */
    @SuppressWarnings("unchecked")
    public <T> T execute(String key, Callable<T> loader) throws Exception {
        if (key == null || key.isBlank()) {
            return loader.call();
        }
        CompletableFuture<Object> future = new CompletableFuture<>();
        CompletableFuture<Object> running = flights.putIfAbsent(key, future);
        if (running == null) {
            try {
                T value = loader.call();
                future.complete(value);
                return value;
            } catch (Throwable t) {
                future.completeExceptionally(t);
                throw t;
            } finally {
                flights.remove(key, future);
            }
        }
        log.debug("同键解析已在进行中，等待合流结果：{}", key);
        try {
            return (T) running.get(waitTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("等待同键解析超时 {}ms，本线程自行解析：{}", waitTimeoutMillis, key);
            return loader.call();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return loader.call();
        }
    }

    /** 当前正在合流的键数量（观测/自检测试用） */
    public int inFlightCount() {
        return flights.size();
    }
}
