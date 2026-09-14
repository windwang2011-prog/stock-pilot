# StockPilot App（移动端）

把网页版 StockPilot 的**分析能力**做成手机 App：交易时段后台实时分析、买卖信号推送、盘后汇总报告。

> 本目录当前包含**平台无关的核心引擎**（TypeScript），已完成并通过真实行情验证。
> 原生外壳（鸿蒙 ArkTS / 安卓 Kotlin）在此基础上叠加，见「路线图」。

---

## 一、结论与关键约束（先看这个）

| 问题 | 结论 |
|---|---|
| 手机端能不能跑现在的 Node 服务？ | **不能**。手机没有 Node 运行时，必须把「数据获取 + 策略」搬进 App。 |
| 后台持续运行怎么做？ | 安卓/鸿蒙都**必须用前台服务（长时任务）+ 常驻通知**，否则系统会杀进程。 |
| 弹窗提示？ | 后台只能发**系统通知**（可点击直达个股）；应用内弹窗仅在前台可用。 |
| 国产手机注意 | 需要用户手动把 App 加入**电池优化白名单 / 允许自启动**，否则会被清理。 |
| 纯血鸿蒙 + 卓易通 | 卓易通是第三方兼容层，**后台存活 / 前台服务 / 系统通知** 是最可能失效的三项，建议先用「探针 APK」验证。 |

---

## 二、架构

```
┌─────────────────── 平台无关核心（core/*.ts）───────────────────┐
│  datasource.ts  行情/K线/板块/搜索（东财为主 + 腾讯备用， 	      │
│                 多候选降级 + 按域名熔断 + 缓存）                 │
│  strategy.ts    指标 + 日线技术面 + 盘前/盘中/盘后分时段分析 + 回测 │
│  engine.ts      盯盘调度 + 信号决策 + 通知去重                    │
│  report.ts      盘后汇总报告生成                                  │
│  store.ts       存储抽象（自选/动作/报告）                         │
└───────────────▲───────────────────────────▲────────────────────┘
                │ HttpClient(注入)           │ KeyValueStore(注入)
      ┌─────────┴─────────┐        ┌────────┴─────────┐
      │ 鸿蒙: @ohos.net.http│        │ 鸿蒙: preferences │
      │ 安卓: OkHttp        │        │ 安卓: Room/SP     │
      │ Node: nodeHttp.ts   │        │ Node: MemoryStore │
      └───────────────────┘        └──────────────────┘
```

核心层零平台依赖，靠**依赖注入**适配各平台，因此同一套策略逻辑只维护一份。

---

## 三、目录结构

```
stockpilot-app/
├── core/                     # 平台无关核心（唯一可信来源）
│   ├── types.ts
│   ├── strategy.ts
│   ├── datasource.ts
│   ├── engine.ts
│   ├── report.ts
│   └── store.ts
├── platform/node/            # 开发期测试用（Node 实现）
│   └── nodeHttp.ts
├── test/
│   ├── logic.test.ts         # 离线逻辑断言（33 项）
│   └── live.ts               # 真实行情端到端
├── scripts/
│   └── prepare-harmony.mjs   # 同步 core -> 鸿蒙工程（去 .ts 扩展名）
├── harmony/                  # 鸿蒙 NEXT 工程（ArkTS + ArkUI）
└── android/                  # 安卓工程（Kotlin + Compose）
```

---

## 四、运行与验证

```bash
# 需要 Node 22+（本项目用 Node 22 的类型擦除直接运行 TS）
node --experimental-strip-types test/logic.test.ts   # 离线逻辑（无需网络）
node --experimental-strip-types test/live.ts         # 真实行情端到端
```

`test/live.ts` 会真实拉取行情，输出：分时段信号、主力资金、量比、通知决策、盘后报告。

> 说明：核心文件用**显式 `.ts` 扩展名**导入，以便 Node 直接运行验证；
> 鸿蒙工程用 `node scripts/prepare-harmony.mjs` 同步时会自动去掉扩展名（ArkTS 习惯）。

---

## 五、路线图

- [x] **M1 核心引擎**：策略/数据/盯盘/日报，离线 33 项断言 + 真实行情端到端通过
- [x] **M2 探针 APK 工程**：`android/probe/`（零第三方依赖，前台服务 + 通知 + 开机自启 + 定时）
      + `.github/workflows/build.yml`（云端自动出 APK）——待装到卓易通验证三项后台能力
- [ ] **M3 完整安卓 App**：Compose 原生界面（自选/推荐/报告/设置）、OkHttp 取数、
      Room 存储、前台服务盯盘、WorkManager 盘后报告；GitHub Actions 云端打包 APK
- [ ] **M4 鸿蒙 NEXT 版**：ArkTS + ArkUI 界面、`@ohos.net.http` 取数、
      `ContinuousTask` 长时任务 + `notificationManager` 通知；DevEco Studio 签名打包

### 为什么 M2 要先做
兼容层对 Android 后台语义的支持程度未知。若 M2 发现后台/通知不可用，
鸿蒙用户就必须走 M4（原生 ArkTS），可以避免在安卓版上白投入。

---

## 六、合规与风险

- 行情来自公开接口的**非官方用法**，App 化后请求频率上升可能被限流；
  建议仅**自用**，不要上架应用商店（金融类上架需资质）。
- 策略为技术指标分析，**不构成投资建议**。
- 交易日判断目前按「工作日」处理，**不含法定节假日**（后续可接入交易日历）。
