// core/http.ts
// HTTP 抽象：核心层不直接依赖任何平台网络库。
// 鸿蒙用 @ohos.net.http 实现，Node 用 platform/node/nodeHttp.ts 实现（用于测试）。

export interface HttpResult {
  ok: boolean;
  status: number;
  text: string;
}

export interface HttpClient {
  get(url: string, timeoutMs: number, headers: Record<string, string>): Promise<HttpResult>;
}
