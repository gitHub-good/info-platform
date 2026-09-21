/**
 * DDD layer 'infrastructure' / domain 'subscription'.
 *
 * <p>自选清单仓储实现：{@link com.info.platform.infrastructure.subscription.WatchlistRepositoryImpl} 实现
 * {@link com.info.platform.domain.subscription.WatchlistRepository} 端口，所有数据查询 {@code WHERE
 * user_id=?} 行级约束，PO↔Entity 转换集中于此。
 *
 * <p>Scaffold per tech design sec 2 (Container) & sec 4.4 (DDD layering, ADR-0007). Cross-domain
 * collaboration goes via domain events; no cross-domain inner-class import.
 */
package com.info.platform.infrastructure.subscription;
