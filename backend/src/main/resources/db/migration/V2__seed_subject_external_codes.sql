-- T03 · 示例标的 external_codes 主数据（东财 secid 为主取数键）
-- 来源：Spike-1 §5 代码体系映射 + 技术方案 §4.2 subject_master.external_codes
-- eastmoney 键存东财 secid：沪 1.代码 / 深 0.代码 / 港 116.代码
-- JSON 内仅双引号，SQLite 单引号字符串包裹无需转义
INSERT INTO subject_master
  (subject_code, market, subject_type, name, external_codes, industry, status, created_at, updated_at, version)
VALUES
  ('SH600519', 'A_SHARE', 1, '贵州茅台',
   '{"eastmoney":"1.600519","tushare":"600519.SH"}', '白酒', 1,
   '2026-09-21T00:00:00Z', '2026-09-21T00:00:00Z', 0),
  ('SZ000001', 'A_SHARE', 1, '平安银行',
   '{"eastmoney":"0.000001","tushare":"000001.SZ"}', '银行', 1,
   '2026-09-21T00:00:00Z', '2026-09-21T00:00:00Z', 0),
  ('HK00700', 'HK', 1, '腾讯控股',
   '{"eastmoney":"116.00700","tushare":"00700.HK"}', '互联网', 1,
   '2026-09-21T00:00:00Z', '2026-09-21T00:00:00Z', 0);
