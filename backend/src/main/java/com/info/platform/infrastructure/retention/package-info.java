/**
 * 留痕生命周期治理的基础设施实现（M10，ADR-0036）：{@link
 * com.info.platform.infrastructure.retention.ExpiredLogDeleterImpl} 删除端口（JdbcTemplate 子查询分批 DELETE，
 * SubjectRepositoryImpl 先例）与 {@code RetentionConfigFacadeImpl} 窗口读写门面。
 */
package com.info.platform.infrastructure.retention;
