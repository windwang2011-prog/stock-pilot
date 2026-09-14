// core/store.ts
// 存储抽象：鸿蒙用 @ohos.data.preferences，Node 测试用内存实现。
// 保存自选列表、上次动作（通知去重）、历史报告。

import type { StockRef } from './types.ts';

export interface KeyValueStore {
  getString(key: string): Promise<string | null>;
  putString(key: string, value: string): Promise<void>;
}

export const KEY_WATCHLIST = 'watchlist';
export const KEY_LAST_ACTION = 'lastAction';
export const KEY_REPORTS = 'reports';

export async function loadWatchlist(store: KeyValueStore): Promise<StockRef[]> {
  const s = await store.getString(KEY_WATCHLIST);
  if (!s) return [];
  try { return JSON.parse(s) as StockRef[]; } catch (e) { return []; }
}

export async function saveWatchlist(store: KeyValueStore, list: StockRef[]): Promise<void> {
  await store.putString(KEY_WATCHLIST, JSON.stringify(list));
}

export async function appendReport(store: KeyValueStore, date: string, text: string): Promise<void> {
  const s = await store.getString(KEY_REPORTS);
  let arr: { date: string; text: string }[] = [];
  if (s) { try { arr = JSON.parse(s) as { date: string; text: string }[]; } catch (e) { arr = []; } }
  arr = arr.filter(r => r.date !== date);
  arr.unshift({ date, text });
  await store.putString(KEY_REPORTS, JSON.stringify(arr.slice(0, 60)));
}

export async function loadReports(store: KeyValueStore): Promise<{ date: string; text: string }[]> {
  const s = await store.getString(KEY_REPORTS);
  if (!s) return [];
  try { return JSON.parse(s) as { date: string; text: string }[]; } catch (e) { return []; }
}

// Node 测试用的内存实现
export class MemoryStore implements KeyValueStore {
  private m: Map<string, string> = new Map<string, string>();
  getString(key: string): Promise<string | null> {
    const v = this.m.get(key);
    return Promise.resolve(v == null ? null : v);
  }
  putString(key: string, value: string): Promise<void> {
    this.m.set(key, value);
    return Promise.resolve();
  }
}
