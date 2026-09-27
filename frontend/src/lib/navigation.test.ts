import { afterEach, describe, expect, it } from 'vitest';
import {
  canonicalizeRoute,
  DEFAULT_SUBJECT_CODE,
  lastViewedSubject,
  navigate,
  parseSubjectCode,
  queryOf,
  rememberSubject,
  sourcesTabOf,
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

  // —— V2.3-M23 T204：源页路由归一 + /sources Tab 解析 ——

  it('canonicalizeRoute：datasource-config → /sources?tab=biz（query 合流透传）', () => {
    expect(canonicalizeRoute('/datasource-config')).toBe('/sources?tab=biz');
    expect(canonicalizeRoute('/datasource-config?foo=1')).toBe('/sources?foo=1&tab=biz');
    // 已带 tab 参数不重复覆写
    expect(canonicalizeRoute('/datasource-config?tab=info')).toBe('/sources?tab=info');
  });

  it('canonicalizeRoute：info-sources → /sources（?source= 定位参数原样透传）', () => {
    expect(canonicalizeRoute('/info-sources')).toBe('/sources');
    expect(canonicalizeRoute('/info-sources?source=mw_topstories')).toBe(
      '/sources?source=mw_topstories',
    );
  });

  it('canonicalizeRoute：非旧路由原样返回（归一层零误伤）', () => {
    expect(canonicalizeRoute('/overview')).toBe('/overview');
    expect(canonicalizeRoute('/sources?tab=biz')).toBe('/sources?tab=biz');
    expect(canonicalizeRoute('/feed-dashboard')).toBe('/feed-dashboard');
  });

  it('sourcesTabOf：?tab=biz → biz；缺省/非法值 → info（默认高频运维面）', () => {
    expect(sourcesTabOf('/sources')).toBe('info');
    expect(sourcesTabOf('/sources?tab=biz')).toBe('biz');
    expect(sourcesTabOf('/sources?tab=info&source=s1')).toBe('info');
    expect(sourcesTabOf('/sources?tab=unknown')).toBe('info');
  });
});
