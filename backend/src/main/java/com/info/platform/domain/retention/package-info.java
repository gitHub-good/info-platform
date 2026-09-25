/**
 * 留痕数据生命周期治理（M10，REQ-20260925-07 / ADR-0036）：四张旁路留痕表的保留窗口与过期删除。
 *
 * <p>领域层承载删除白名单的单一事实源（{@link com.info.platform.domain.retention.RetentionLogTable}）与删除端口
 * {@link com.info.platform.domain.retention.ExpiredLogDeleter}（签名收枚举不收字符串——业务表名无从传入，无注入面）。
 * 消费编排在应用层 {@code com.info.platform.application.retention}，端口实现在基础设施层
 * {@code com.info.platform.infrastructure.retention}（JdbcTemplate，SubjectRepositoryImpl 先例）。
 */
package com.info.platform.domain.retention;
