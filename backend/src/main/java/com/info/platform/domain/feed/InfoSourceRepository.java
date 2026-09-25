package com.info.platform.domain.feed;

import java.util.List;
import java.util.Optional;

/**
 * 资讯源注册表仓储端口（依赖倒置：领域层定义、基础设施层实现，M13 T100）。
 *
 * <p>领域层纯净接口，不依赖 MyBatis/Spring 等框架类型。
 */
public interface InfoSourceRepository {

    /** 按 id 查找（PATCH/DELETE/手动抓取入口）。 */
    Optional<InfoSource> findById(Long id);

    /** 按稳定代码查找（UNIQUE(source_code)；种子幂等判定用）。 */
    Optional<InfoSource> findBySourceCode(String sourceCode);

    /** 全部启用且未软删的源（调度 tick 每轮现读 = 配置热生效；enabled=1 AND deleted=0）。 */
    List<InfoSource> findActive();

    /** 全部源（含停用/软删，列表页用）。 */
    List<InfoSource> findAll();

    /** 保存（id 空 = INSERT 并回填，否则 UPDATE 全字段）。 */
    InfoSource save(InfoSource source);

    /**
     * seed-if-absent 新增（按 UNIQUE(source_code) 幂等，冲突被 IGNORE）。
     *
     * @return true = 本次插入；false = source_code 已存在（存量行不动，DB 为权威）
     */
    boolean insertIfAbsent(InfoSource source);
}
