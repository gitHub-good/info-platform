/**
 * 资讯源域（feed bounded context，M13，ADR-0038/0039/0040）：源注册表（info_source）、轮询运行态、资讯统一库（news_item）
 * 与取数端口。与既有六源业务域（aggregation，请求驱动 + 降级链）并存、边界冻结——本域为调度驱动 7×24 轮询入库， 失败语义为指数退避摘除。
 *
 * <p>领域层纯净：仅依赖 JDK 类型（时间戳 ISO-8601 惯例），不 import Spring/MyBatis/Jackson。
 */
package com.info.platform.domain.feed;
