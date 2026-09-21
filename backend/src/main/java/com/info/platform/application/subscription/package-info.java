/**
 * DDD layer 'application' / domain 'subscription'.
 *
 * <p>自选清单应用服务：{@link com.info.platform.application.subscription.WatchlistService} 编排 CRUD， 每个方法取
 * {@code UserContext.get().userId()} 落实行级权限、自然键幂等。
 *
 * <p>Scaffold per tech design sec 2 (Container) & sec 4.4 (DDD layering, ADR-0007). Cross-domain
 * collaboration goes via domain events; no cross-domain inner-class import. 加标的校验标的存在时注入 {@link
 * com.info.platform.domain.aggregation.SubjectRepository} 端口（任务单 T11 明确）。
 */
package com.info.platform.application.subscription;
