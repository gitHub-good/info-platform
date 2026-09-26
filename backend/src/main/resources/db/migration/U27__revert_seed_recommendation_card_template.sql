-- U27 · 回滚 V27（删除 brief_type=8 推荐卡片模板行；prompt_template 其余 7 类无伤）
DELETE FROM prompt_template WHERE brief_type = 8;
