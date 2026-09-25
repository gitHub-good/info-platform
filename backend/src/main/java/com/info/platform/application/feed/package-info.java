/**
 * 资讯源域应用服务（M13）：源注册校验（SourceConfigValidator）、单轮摄取（FeedIngestService）、 分钟级聚合调度（SourceSchedulingService +
 * SourcePollJob）、统一资讯流查询（NewsItemsQueryService）。事务边界与调度编排在此层， 领域规则在 domain/feed，外呼与持久化在
 * infrastructure/feed。
 */
package com.info.platform.application.feed;
