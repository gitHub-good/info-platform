/**
 * DDD layer 'interfaces' / domain 'subscription'.
 *
 * <p>自选清单接口：{@link com.info.platform.interfaces.subscription.WatchlistController} 提供
 * GET/POST/DELETE/PATCH 端点，仅做参数校验与编排，行级权限交应用层。所有端点受 JWT 保护（T17）。
 *
 * <p>Scaffold per tech design sec 2 (Container) & sec 4.4 (DDD layering, ADR-0007). Cross-domain
 * collaboration goes via domain events; no cross-domain inner-class import.
 */
package com.info.platform.interfaces.subscription;
