package com.info.platform.infrastructure.aggregation;

import java.nio.charset.Charset;

/**
 * 新浪 vFD 两页实测响应夹具（ADR-0034 T55 测试共用）：数值与行名变体取 2026-09-24 架构 curl 实测口径（技术方案附录 A 与东财同报告期
 * 交叉核对零偏差），HTML 结构按该日实测（GBK、表 id、报告期倒序列、万元含千分位逗号）。
 */
final class SinaFinanceFixtures {

    static final Charset GBK = Charset.forName("GBK");

    /** 2026-09-24 实测口径：贵州茅台利润表（通用变体：一、营业总收入 / 归属于母公司所有者的净利润）。 */
    static final String PROFIT_600519 =
            """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>利润表</title></head><body>
            <div id="main"><table id="ProfitStatementNewTable0" class="dataview"><tbody>
            <tr><th>报表日期</th><td>2026-06-30</td><td>2026-03-31</td><td>2025-12-31</td></tr>
            <tr><th>一、营业总收入</th><td>9,227,807.21</td><td>5,148,585.51</td><td>17,089,935.35</td></tr>
            <tr><th>归属于母公司所有者的净利润</th><td>4,451,688.04</td><td>2,689,742.61</td><td>8,622,842.20</td></tr>
            </tbody></table></div>
            </body></html>
            """;

    /**
     * 贵州茅台财务指标页（表 id 实测为 BalanceSheetNewTable0）：含摊薄口径「净资产收益率(%)」17.72 干扰行—— 加权 16.75 才与东财 ROEJQ
     * 一致（ADR-0034 禁用摊薄行）；毛利率 '--'。
     */
    static final String GUIDE_600519 =
            """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>财务指标</title></head><body>
            <div id="main"><table id="BalanceSheetNewTable0" class="dataview"><tbody>
            <tr><th>报告日期</th><td>2026-06-30</td><td>2026-03-31</td></tr>
            <tr><th>净资产收益率(%)</th><td>17.72</td><td>10.94</td></tr>
            <tr><th>加权净资产收益率(%)</th><td>16.75</td><td>10.29</td></tr>
            <tr><th>销售净利率(%)</th><td>50.7516</td><td>52.2112</td></tr>
            <tr><th>销售毛利率(%)</th><td>--</td><td>--</td></tr>
            </tbody></table></div>
            </body></html>
            """;

    /** 贵州茅台利润表（前年 ctrl 页形态）：年初窗口当年无报告期时回退 year-1 取到的上一年报告期。 */
    static final String PROFIT_600519_PRIOR_YEAR =
            """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>利润表</title></head><body>
            <div id="main"><table id="ProfitStatementNewTable0" class="dataview"><tbody>
            <tr><th>报表日期</th><td>2025-12-31</td><td>2025-09-30</td></tr>
            <tr><th>一、营业总收入</th><td>17,089,935.35</td><td>12,140,510.26</td></tr>
            <tr><th>归属于母公司所有者的净利润</th><td>8,622,842.20</td><td>6,314,973.34</td></tr>
            </tbody></table></div>
            </body></html>
            """;

    /** 贵州茅台财务指标页（前年 ctrl 页形态）。 */
    static final String GUIDE_600519_PRIOR_YEAR =
            """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>财务指标</title></head><body>
            <div id="main"><table id="BalanceSheetNewTable0" class="dataview"><tbody>
            <tr><th>报告日期</th><td>2025-12-31</td><td>2025-09-30</td></tr>
            <tr><th>加权净资产收益率(%)</th><td>36.32</td><td>28.17</td></tr>
            <tr><th>销售净利率(%)</th><td>52.27</td><td>52.94</td></tr>
            </tbody></table></div>
            </body></html>
            """;

    /** 平安银行利润表（金融变体：一、营业收入 / 归属于母公司的净利润）。 */
    static final String PROFIT_000001 =
            """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>利润表</title></head><body>
            <div id="main"><table id="ProfitStatementNewTable0" class="dataview"><tbody>
            <tr><th>报表日期</th><td>2026-06-30</td><td>2025-12-31</td></tr>
            <tr><th>一、营业收入</th><td>7,061,700.00</td><td>14,620,600.00</td></tr>
            <tr><th>归属于母公司的净利润</th><td>2,569,600.00</td><td>5,145,800.00</td></tr>
            </tbody></table></div>
            </body></html>
            """;

    /** 平安银行财务指标页（净利率/毛利率均 '--'，金融股缺失语义）。 */
    static final String GUIDE_000001 =
            """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>财务指标</title></head><body>
            <div id="main"><table id="BalanceSheetNewTable0" class="dataview"><tbody>
            <tr><th>报告日期</th><td>2026-06-30</td><td>2025-12-31</td></tr>
            <tr><th>加权净资产收益率(%)</th><td>5.22</td><td>10.19</td></tr>
            <tr><th>销售净利率(%)</th><td>--</td><td>--</td></tr>
            <tr><th>销售毛利率(%)</th><td>--</td><td>--</td></tr>
            </tbody></table></div>
            </body></html>
            """;

    /** 中国平安利润表（保险变体：归属于母公司股东的净利润）。 */
    static final String PROFIT_601318 =
            """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>利润表</title></head><body>
            <div id="main"><table id="ProfitStatementNewTable0" class="dataview"><tbody>
            <tr><th>报表日期</th><td>2026-06-30</td><td>2025-12-31</td></tr>
            <tr><th>一、营业收入</th><td>57,513,800.00</td><td>114,135,900.00</td></tr>
            <tr><th>归属于母公司股东的净利润</th><td>9,258,500.00</td><td>18,597,800.00</td></tr>
            </tbody></table></div>
            </body></html>
            """;

    /** 中国平安财务指标页。 */
    static final String GUIDE_601318 =
            """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>财务指标</title></head><body>
            <div id="main"><table id="BalanceSheetNewTable0" class="dataview"><tbody>
            <tr><th>报告日期</th><td>2026-06-30</td><td>2025-12-31</td></tr>
            <tr><th>加权净资产收益率(%)</th><td>9.00</td><td>17.65</td></tr>
            <tr><th>销售净利率(%)</th><td>--</td><td>--</td></tr>
            <tr><th>销售毛利率(%)</th><td>--</td><td>--</td></tr>
            </tbody></table></div>
            </body></html>
            """;

    /** 年初窗口当年页形态：表格在但「报表日期」行无数据列（当年报告期未披露）。 */
    static final String PROFIT_EMPTY_YEAR =
            """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>利润表</title></head><body>
            <div id="main"><table id="ProfitStatementNewTable0" class="dataview"><tbody>
            <tr><th>报表日期</th></tr>
            </tbody></table></div>
            </body></html>
            """;

    /** 报告期错位形态：指标页最新列落后利润表一期（披露时点差，比率指标须按利润表报告期对位取列）。 */
    static final String GUIDE_600519_LAGGING =
            """
            <!DOCTYPE html><html><head><meta charset="gbk"><title>财务指标</title></head><body>
            <div id="main"><table id="BalanceSheetNewTable0" class="dataview"><tbody>
            <tr><th>报告日期</th><td>2026-03-31</td><td>2025-12-31</td></tr>
            <tr><th>加权净资产收益率(%)</th><td>10.29</td><td>36.32</td></tr>
            <tr><th>销售净利率(%)</th><td>52.2112</td><td>52.27</td></tr>
            </tbody></table></div>
            </body></html>
            """;

    private SinaFinanceFixtures() {}
}
