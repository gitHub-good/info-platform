package com.info.platform.infrastructure.common;

/** 可抛受检异常的 Supplier（供 {@link ResilienceRunner} 包装子类 doFetch）。 */
@FunctionalInterface
public interface CheckedSupplier<T> {
    T get() throws Exception;
}
