package com.info.platform.domain.markettop;

/**
 * 深析输出引用（M21 T182，方案 §4.4.2/§4.4.5）：结构化 {@code (type,id)} 二元组——EVENT 指向 {@code event_item.id}、NEWS
 * 指向 {@code news_item.id}；引用对账是纯集合比对（比文本 contains 更强，ADR-0059 裁决 3），record 等值语义即比对单元。
 */
public record Citation(String type, long id) {

    /** 事件引用（trace-v1 事件溯源链下钻键）。 */
    public static final String TYPE_EVENT = "EVENT";

    /** 资讯引用（资讯库条目下钻键）。 */
    public static final String TYPE_NEWS = "NEWS";

    public Citation {
        type = type == null ? "" : type;
    }

    /** 工厂：事件引用。 */
    public static Citation event(long eventId) {
        return new Citation(TYPE_EVENT, eventId);
    }

    /** 工厂：资讯引用。 */
    public static Citation news(long newsId) {
        return new Citation(TYPE_NEWS, newsId);
    }

    /** 类型合法（EVENT/NEWS 之外判无效——对账剔除面）。 */
    public boolean knownType() {
        return TYPE_EVENT.equals(type) || TYPE_NEWS.equals(type);
    }
}
