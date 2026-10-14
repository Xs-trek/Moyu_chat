# 账号、路线与模型配置

本文说明 Moyu Chat 怎样接入模型服务：数据结构、能力从哪里来、协议细节怎样描述，以及围绕 API Key 的安全规则。代码以 `ProviderConfigService`、`DeploymentResolver`、`ProviderPresets` 为准。

## 1. 三层结构

| 层 | 保存什么 | 表 |
|---|---|---|
| 账号（Provider） | 名称、服务商预设（创建后不变）、一把 API Key（只在原生安全存储）、密钥状态、预算 | `providers` |
| 路线（Route） | 协议 + Base URL + 鉴权方式 + 协议细节（dialect）+ 附加请求头 + 已确认的密钥域名。协议创建后不变 | `provider_routes` |
| 模型（Model Profile） | 挂在一条路线上的 Model ID；每项能力的生效值、来源、手动值、价格、数据保留说明；可停用 | `model_profiles` |

会话（Session）在创建或切换模型时冻结模型能力和协议；路线（地址、鉴权、协议细节、请求头）实时跟随，因为它描述的是端点，不是这段对话。

同一把 Key 的多种协议（例如 DeepSeek 的 Responses / Chat Completions / Anthropic 格式，OpenCode 的 Chat / Responses / Messages）就是同一个账号下的多条路线。路线按添加顺序排列，预设里的第一条路线是新模型的默认路线；“添加路线”会先列出账号还没有的预设路线。

保存账号时提交的是完整的路线集合：带 `routeId` 的更新原路线；不带的若与一条没被保留的已有路线协议和 Base URL 完全相同，就沿用那条路线（ID、域名确认和模型都保留），否则新建；被去掉的路线先删除再写入。

### Base URL 写法

按各协议官方 SDK 的写法填写，和厂商文档给出的值一致：

| 协议 | Base URL | 请求地址 |
|---|---|---|
| Chat Completions | 含版本段，如 `https://api.openai.com/v1` | `…/chat/completions` |
| Responses | 含版本段，如 `https://api.openai.com/v1` | `…/responses` |
| Anthropic Messages | 不含 `/v1`，如 `https://api.anthropic.com` | `…/v1/messages` |

## 2. 服务商预设与模型目录

`android/app/src/main/resources/com/moyu/chat/provider-presets.json` 是带版本号的数据文件，包含：

- **presets**：每个服务商的路线（协议、地址、鉴权、协议细节）、控制台链接、价格页、建议的附加请求头。目前有 OpenAI、Anthropic、DeepSeek、OpenCode Zen、OpenCode Go，以及两个自定义模板。
- **models**：已审核的模型条目，键是“预设 + 路线 + Model ID”。可声明上下文和输出上限、输入模态、推理参数和档位、允许的思考模式、厂商联网工具、客户端工具、续接、Harness 档位、价格、数据保留。`verified: true` 表示来自厂商官方文档；服务商（OpenCode）条目标为“未实测”。

目录只在路线仍是**原样的预设路线**时生效：同一预设、同一预设路线、同一协议、规范化后完全相同的 Base URL，且 Model ID 完全一致。改过地址的路线、自定义路线、相似域名、相似 Model ID 都拿不到目录里的任何声明。

浏览器预览直接读取同一个 JSON（`dev/serve.mjs` 把它作为模块提供），不另存副本。

### 维护

- 厂商调价、发新模型时改这个文件并更新 `version`。启动时，目录版本不同的模型会按新目录重新合成（手动值不受影响）。
- `ProviderPresetsTest` 会检查每条预设路线都是合法的 HTTPS 端点，每个目录条目在它的路线上都能合成为通过校验的模型；`static-check.mjs` 检查数据文件的基本结构。

## 3. 能力合成：每项都有来源

`DeploymentResolver` 按层合成，后面的覆盖前面的：

| 来源 | 说明 |
|---|---|
| 默认 | 保守值：不发推理参数、不提供工具、只收文本、上下文 128k、输出预留 16k（Messages 32k） |
| 端点（discovered） | 端点自己的模型列表报告的上下文、输出上限、图片输入（见第 7 节） |
| 目录 | 上一节的已审核条目 |
| 实测（probe） | 预留层；连接测试只记录结果，目前不设置任何字段 |
| 手动 | 用户逐项填写的值，单独保存在 `overrides`，目录更新不会覆盖 |

可覆盖的字段：上下文窗口、输入上限、输出上限、输入/输出模态、推理参数、推理档位、思考模式、手动思考预算、厂商联网工具、工具调用、跨轮续接、Harness 档位、服务层级、价格。未知字段一律拒绝。

合成后的结果仍要通过 `ModelProfileValidator` 对路线的校验；目录声明了允许的思考模式时（例如 Opus 5.5 不能关闭思考），手动值也必须在其中。

**输出上限**：只有被声明过（目录、端点、实测或手动）才发送；未声明时由服务端决定，本地只用默认值做预算。Messages 协议必须带 `max_tokens`，始终发送。

## 4. 协议细节（dialect）

描述端点怎样拼写协议里留空的可选字段。由预设填好，可在路线的“协议细节”里修改；未知键一律拒绝。

| 协议 | 键 | 取值 | 作用 |
|---|---|---|---|
| Chat | `outputCap` | `max_tokens` / `max_completion_tokens` / `omit` | 输出上限用哪个字段；OpenAI 官方推理模型要求 `max_completion_tokens` |
| Chat | `streamUsage` | 布尔 | 发送 `stream_options.include_usage`，否则流式没有用量 |
| Chat | `thinkingParam` | `none` / `thinking-type` | `thinking: {type: enabled\|disabled}`（DeepSeek、GLM、Kimi 等） |
| Chat | `reasoningReplay` | `never` / `with-tools` / `always` | 把助手的 `reasoning_content` 传回去：DeepSeek 带工具时必须，Kimi 思考模型每轮都要 |
| Responses | `outputCap` | `max_output_tokens` / `omit` | |
| Responses | `store` | 布尔，默认 `false` | 不让服务端保存回答；续接用加密推理内容 |
| Messages | `autoCache` | 布尔 | 请求顶层 `cache_control`，服务端自动缓存对话前缀 |
| 全部 | `maxConcurrent` | 0–16 | 这条路线同时进行的主调用数，0 = 不限（第 7 节） |

推理文本两种字段都解析（`reasoning_content`、`reasoning`），不需要配置。

推理回传和原生续接是两回事：续接是可选的优化，受上下文模式和 `continuationMode` 控制；推理回传是端点的硬性要求，路线声明了就在所有模式下执行，否则请求会失败。

## 5. 安全规则

| 规则 | 实现 |
|---|---|
| Key 只在原生安全存储 | 页面只能打开原生密码框（`provider.credential.edit`），任何意图都不收发 Key |
| 粘贴检查 | 原生层去掉首尾空白，拒绝中间的空格、换行和非 ASCII 字符；错误提示不回显 Key |
| 只发往确认过的域名 | 填写 Key 时，账号所有路线的当前域名记为已确认；之后改了地址的路线，必须在账号页确认新域名才会发送请求。原生密码框会列出 Key 将发往的域名 |
| 不跟随重定向 | 两个传输层都关闭重定向：OkHttp 跨域跳转只去掉 `Authorization`，`x-api-key` / `api-key` 和 307/308 的请求体都会被带过去 |
| 只信任系统证书 | 网络安全配置不信任用户安装的证书，代理无法解密流量；只允许 HTTPS（本机回环地址除外） |
| 附加请求头不放机密 | 名称含 key、token、secret、auth、session 等字样的，以及传输层自己管理的头，一律拒绝；值只能是可见 ASCII |
| 不离开手机 | `allowBackup=false` 加 Android 12+ 的 `dataExtractionRules`：云备份和设备间迁移都不带走会话和设置 |
| 删除账号 | 路线、模型和 Key 一起删除；已有会话保留可读，要切换模型才能继续 |

## 6. 错误提示

`ProviderErrors` 把失败分成 Key 无效、余额或额度不足、无权限、出口地区不受支持（提示检查代理节点）、端点或模型不存在、上下文超长、限流、服务过载、服务端错误，以及网络层的域名解析、连接、TLS、超时（提示检查网络或代理）。提示里带上服务商的请求 ID（`x-request-id` / `request-id`）。限流和服务端错误可重试；额度不足不可重试。

## 7. 账号检查与花费（provider-guard）

代码在 `ProviderGuard`、`RouteGate`、`ProviderConfigService`（预算）、`TurnUsage`（费用）、`ProviderRoundLoop`（重试）。规则和传输层一样：Key 只发往已确认的域名，不跟随重定向，返回内容里没有 Key。

| 功能 | 做法 |
|---|---|
| 检查 Key | 调用不计费的模型列表（OpenAI 风格 `GET …/models`，Messages 风格 `GET …/v1/models`），优先用预设里声明了 `listModels` 的路线。2xx = 可用，401 = 无效，404/405 = 端点没有列表（未验证），其它 = 检查失败。填写新 Key 后页面自动检查一次；每个正常完成的回合也会把状态记为可用，401 记为无效 |
| 端点模型列表 | 模型编辑页的“端点列表”可以直接选 Model ID。列表报告的上下文、输出上限、图片输入成为该模型的“端点（discovered）”来源；不合理的数值丢弃，会让模型校验失败的数值不采用。列表里没有的 Model ID 在账号页提示 |
| 连接测试 | 模型卡片上的“测试”发一个极小的请求，记录成败、首字和总耗时、有没有用量、有没有推理文本，存在模型的 `probe.result`。**它不改变任何能力值**（实测层目前没有字段） |
| 余额 | 预设路线声明了 `balancePath` 的服务商（目前是 DeepSeek）可以在账号页查余额，结果不保存 |
| 费用 | 回合接受时冻结模型价格；每次调用（含辅助调用、子任务）按自己的提示长度和时间估价后求和，写进用量的 `cost`。有调用没报用量时标为 `partial`（实际更高） |
| 预算 | 账号可设每日 / 每月预算（USD 或 CNY，只计入同币种的费用，按手机本地日历）。达到后每一轮发送前都要确认（`budgetConfirmed`），重试同样 |
| 限流重试 | 主调用在还没有任何输出时遇到 429（非额度不足）、408、409、529、5xx，最多自动重试 2 次：有 `Retry-After` 就按它（要求超过 60 秒则不等），否则 2 秒、6 秒。等待时显示“服务繁忙，稍后自动重试”，可以停止。次数记在用量的 `providerRetries` 和调用链里 |
| 路线并发上限 | 路线协议细节里的 `maxConcurrent`（0 = 不限，最多 16），所有协议都可用。超出的回合在主调用前排队（“排队中”），有空位时自动开始；辅助调用不受限 |
