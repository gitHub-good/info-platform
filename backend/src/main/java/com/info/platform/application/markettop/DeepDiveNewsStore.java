package com.info.platform.application.markettop;

import com.info.platform.domain.markettop.DeepDiveInput.NewsFact;
import java.util.List;

/**
 * 深析输入资讯投影端口（M21 T182，方案 §4.4.2）：关联资讯（matched 回联窗内最近）与行业资讯（main_category=标的 SW 行业 7 日最近）。
 *
 * <p>端口在应用层、实现在基础设施层（{@code infrastructure.markettop.DeepDiveNewsStoreImpl}，JdbcTemplate）——
 * IndustryMemberStore 同款分层；DeepDiveService 不感知 SQL。
 */
public interface DeepDiveNewsStore {

    /**
     * 标的关联资讯（news_analysis DONE 且 matched_subjects 含该代码，published_at 降序取前 cap 条）。
     *
     * @param subjectCode 标的代码（subject_master.subject_code 口径，如 SZ300024）
     * @param fromIso 窗口起点（含，ISO-8601 整秒）
     * @param cap 条数上限（§4.4.2 cap 8）
     */
    List<NewsFact> findRelatedNews(String subjectCode, String fromIso, int cap);

    /**
     * 行业资讯（main_category = 申万行业，published_at 降序取前 cap 条）。
     *
     * @param swIndustry 申万一级行业名（swPrimaryOf 映射输出）
     * @param fromIso 窗口起点（含，7 日窗）
     * @param cap 条数上限（§4.4.2 cap 3）
     */
    List<NewsFact> findIndustryNews(String swIndustry, String fromIso, int cap);
}
