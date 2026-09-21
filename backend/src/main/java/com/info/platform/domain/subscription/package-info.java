/**
 * DDD layer 'domain' / domain 'subscription'.
 *
 * <p>个人化跟踪域：自选清单（watchlist）与订阅配置。两类资源均 user_id 行级权限、 业务语义键幂等。watchlist（T11）归本域： 与订阅同属用户个性化标的跟踪，T26
 * 订阅配置依赖 T11，同域避免跨域 import。
 *
 * <p>Scaffold per tech design sec 2 (Container) & sec 4.4 (DDD layering, ADR-0007). Cross-domain
 * collaboration goes via domain events; no cross-domain inner-class import. 端口接口可跨域被注入 （{@link
 * com.info.platform.domain.aggregation.SubjectRepository} 供加标的时校验标的存在）。
 */
package com.info.platform.domain.subscription;
