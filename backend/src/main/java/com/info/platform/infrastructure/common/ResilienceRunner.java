package com.info.platform.infrastructure.common;

import com.info.platform.domain.aggregation.SourceCode;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 弹性执行器：超时（{@code Future.get(timeout)} + {@code cancel(true)} 中断工作线程）+ 指数退避重试（仅幂等只读）。
 *
 * <p>选型见 ADR-0010：自建（JDK only），不引入 Resilience4j / Spring Retry。 虚拟线程执行器（Java 21）承载阻塞式外部调用； 超时后
 * {@code cancel(true)} 中断工作线程，避免线程泄漏。 熔断由 {@link CircuitBreaker} 单独承载（本类不感知，由模板层调用 allowRequest /
 * recordSuccess / recordFailure）。
 *
 * <p>对齐方案 07 弹性四件套——本类实现「超时 + 重试」两件，熔断与降级在模板层编排。
 */
public class ResilienceRunner {

    private static final Logger log = LoggerFactory.getLogger(ResilienceRunner.class);

    /** 退避抖动比例（退避时长 × [0, JITTER) 随机量），防同步重试雪崩。 */
    private static final double JITTER = 0.25;

    /** 退避指数位移上限，防溢出。 */
    private static final int MAX_BACKOFF_SHIFT = 6;

    private final ExecutorService executor;

    public ResilienceRunner(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor 必填");
    }

    /**
     * 以超时 + 重试执行 {@code action}。
     *
     * @param action 取数动作（可抛异常）
     * @param spec 超时/重试配置
     * @param code 数据源标识（仅日志上下文）
     * @return action 的返回值（成功）
     * @throws ResilienceException 超时 / 重试耗尽 / 中断
     */
    public <T> T run(CheckedSupplier<T> action, ResilienceSpec spec, SourceCode code)
            throws ResilienceException {
        int attempt = 0;
        int maxAttempts = spec.maxRetries() + 1;
        while (true) {
            attempt++;
            Future<T> future = executor.submit(action::get);
            try {
                return future.get(spec.timeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                log.warn(
                        "数据源取数超时 sourceCode={} attempt={}/{} timeoutMs={}",
                        code,
                        attempt,
                        maxAttempts,
                        spec.timeout().toMillis());
            } catch (ExecutionException ee) {
                log.warn(
                        "数据源取数异常 sourceCode={} attempt={}/{} cause={}",
                        code,
                        attempt,
                        maxAttempts,
                        String.valueOf(ee.getCause()));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ResilienceException("interrupted sourceCode=" + code, ie);
            }
            if (attempt >= maxAttempts) {
                throw new ResilienceException("exhausted sourceCode=" + code);
            }
            backoff(attempt, spec, code);
        }
    }

    private void backoff(int attempt, ResilienceSpec spec, SourceCode code)
            throws ResilienceException {
        if (spec.backoffBase().isZero()) {
            return;
        }
        long base = spec.backoffBase().toMillis();
        long exponential = base * (1L << Math.min(attempt - 1, MAX_BACKOFF_SHIFT));
        long jitter = (long) (exponential * JITTER * Math.random());
        try {
            Thread.sleep(exponential + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResilienceException("backoff-interrupted sourceCode=" + code, e);
        }
    }
}
