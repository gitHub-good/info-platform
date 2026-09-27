import { afterEach, describe, expect, it } from 'vitest';
import {
  canonicalizeRoute,
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

  // —— V2.3-M23 T204 源页归一 → V2.4 T212 去 Tab 修订：?tab= 退役、?section=biz 段定位 ——

  it('canonicalizeRoute：datasource-config → /sources?section=biz（query 合流透传）', () => {
    expect(canonicalizeRoute('/datasource-config')).toBe('/sources?section=biz');
    expect(canonicalizeRoute('/datasource-config?foo=1')).toBe('/sources?foo=1&section=biz');
    // 旧 ?tab= 参数一并归一（biz → section；info 静默删除）
    expect(canonicalizeRoute('/datasource-config?tab=info')).toBe('/sources');
    expect(canonicalizeRoute('/datasource-config?tab=biz')).toBe('/sources?section=biz');
  });

  it('canonicalizeRoute：info-sources → /sources（?source= 定位参数原样透传）', () => {
    expect(canonicalizeRoute('/info-sources')).toBe('/sources');
    expect(canonicalizeRoute('/info-sources?source=mw_topstories')).toBe(
      '/sources?source=mw_topstories',
    );
  });

  it('canonicalizeRoute：?tab= 退役归一——/sources?tab=biz → ?section=biz；tab=info 静默删除', () => {
    expect(canonicalizeRoute('/sources?tab=biz')).toBe('/sources?section=biz');
    expect(canonicalizeRoute('/sources?tab=info')).toBe('/sources');
    expect(canonicalizeRoute('/sources?tab=info&source=s1')).toBe('/sources?source=s1');
    expect(canonicalizeRoute('/sources?tab=unknown')).toBe('/sources');
    // section 已显式时不重复覆写；其余参数原样保留
    expect(canonicalizeRoute('/sources?section=biz&tab=biz')).toBe('/sources?section=biz');
  });

  // —— V2.4 T213：政策时事页裁撤——#/policies（含详情子路由）重定向资讯库预填 ——

  it('canonicalizeRoute：/policies 与 /policies/{id} → /news-library?l1=监管·政策（预填重定向）', () => {
    expect(canonicalizeRoute('/policies')).toBe('/news-library?l1=监管·政策');
    expect(canonicalizeRoute('/policies/12345')).toBe('/news-library?l1=监管·政策');
    expect(canonicalizeRoute('/policies?days=7')).toBe('/news-library?l1=监管·政策');
  });

  it('canonicalizeRoute：非旧路由原样返回（归一层零误伤）', () => {
    expect(canonicalizeRoute('/overview')).toBe('/overview');
    expect(canonicalizeRoute('/sources?source=s1')).toBe('/sources?source=s1');
    expect(canonicalizeRoute('/sources')).toBe('/sources');
    expect(canonicalizeRoute('/feed-dashboard')).toBe('/feed-dashboard');
  });
});
