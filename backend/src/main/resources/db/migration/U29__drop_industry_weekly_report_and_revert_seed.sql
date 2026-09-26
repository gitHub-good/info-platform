-- U29 · 回滚 V29（M17 T145/T146 周报表 + brief_type=9 模板播种）。
DELETE FROM prompt_template WHERE brief_type = 9 AND version = 'v1.0';
DROP TABLE IF EXISTS industry_weekly_report;
