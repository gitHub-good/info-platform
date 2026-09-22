import { afterEach, describe, expect, it } from 'vitest';
import {
  DEFAULT_SUBJECT_CODE,
  lastViewedSubject,
  navigate,
  parseSubjectCode,
  queryOf,
  rememberSubject,
} from '@/lib/navigation';

afterEach(() => {
  window.location.hash = '';
  localStorage.clear();
});

describe('navigation 路由工具', () => {
  it('navigate：设置目标 hash（自动补 #）；与当前一致时不重复赋值', () => {
    navigate('/overview');
    expect(window.location.hash).toBe('#/overview');
    navigate('#/overview'); // 已在目标页，无变化
    expect(window.location.hash).toBe('#/overview');
    navigate('/watchlists');
    expect(window.location.hash).toBe('#/watchlists');
  });

  it('queryOf：解析路由查询串（#/job-logs?jobName=x → jobName=x）', () => {
    expect(queryOf('/job-logs?jobName=PolicyFetchJob').get('jobName')).toBe('PolicyFetchJob');
    expect(queryOf('/job-logs').get('jobName')).toBeNull();
    expect(queryOf('').get('jobName')).toBeNull();
  });

  it('parseSubjectCode：路径段 / 查询参数两种形态，无参回退最近浏览标的，无历史回默认', () => {
    // 主路径：#/subjects/:code
    expect(parseSubjectCode('/subjects/SZ000001')).toBe('SZ000001');
    expect(parseSubjectCode('/subjects/SH600519?tab=quote')).toBe('SH600519');
    // query 形态：#/subjects?code=xxx
    expect(parseSubjectCode('/subjects?code=HK00700')).toBe('HK00700');
    // 无参：无历史回默认标的；有历史回最近浏览（侧栏「标的详情」入口口径）
    expect(parseSubjectCode('/subjects')).toBe(DEFAULT_SUBJECT_CODE);
    rememberSubject('SZ000001');
    expect(parseSubjectCode('/subjects')).toBe('SZ000001');
    expect(parseSubjectCode('/subjects?other=1')).toBe('SZ000001');
  });

  it('最近浏览标的：rememberSubject 写入、lastViewedSubject 读取，无历史回默认', () => {
    expect(lastViewedSubject()).toBe(DEFAULT_SUBJECT_CODE);
    rememberSubject('SZ000001');
    expect(lastViewedSubject()).toBe('SZ000001');
    // 侧栏入口形态：href 指向最近浏览标的
    expect(`#/subjects/${lastViewedSubject()}`).toBe('#/subjects/SZ000001');
  });
});
