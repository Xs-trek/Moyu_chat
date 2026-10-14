# 界面设计约束

Moyu Chat 的界面全部由 WebView 绘制，用同一套 token、组件和动效。本文是前端改动的依据；`scripts/static-check.mjs` 会检查其中能机械判断的部分。

## 1. 原生与 WebView 的分工

界面一律在 WebView 里实现。只有下面三处保留原生，各有原因：

| 原生部分 | 原因 |
|---|---|
| API Key 输入框（`MainActivity.openCredentialEditor`） | 密钥只能进安全存储，不能经过页面。它由前端的“填写 / 更换”按钮通过 `provider.credential.edit` 打开，系统密码输入框，保存后直接写入 `ChatSecretStore` |
| 系统图片选择器 | 读取相册必须走系统组件 |
| 外部浏览器 | 来源链接只能交给系统浏览器打开（仅 HTTPS） |

此外：

- 不用 `<select>`：WebView 会把它弹成系统原生对话框，风格和 App 不一致。少量选项用分段按钮；选项多或需要说明时，用 App 自己的选择面板（`openChoice`）。
- 不用系统 Toast：原生要提示时发 `type: 'notice'` 信封，由页面的提示条显示。
- 编辑页里供选择的取值（协议、鉴权方式等）由原生的 `model.editor.options` 提供，前端不硬编码校验器的词表。

## 2. Token

颜色、字号、圆角都在 `src/styles.css` 顶部的 `:root` 中定义，深色和三种底色只重新定义 token。组件里不写字面颜色（页面压暗用的 `#000` 遮罩除外）。窗口和系统栏颜色由原生按 `--canvas` 同步。

动效 token：

| Token | 值 | 用途 |
|---|---|---|
| `--d-press` | 90ms | 按压缩放 |
| `--d-small` | 200ms | 颜色、透明度、小控件状态 |
| `--d-medium` | 300ms | 展开收起、分段滑块、返回 |
| `--d-page` | 380ms | 页面推入、底部面板进入 |
| `--d-exit` | 200ms | 退出、收起（退出总比进入快） |
| `--e-standard` | `cubic-bezier(.2, 0, 0, 1)` | 状态变化 |
| `--e-decel` | `cubic-bezier(.05, .7, .1, 1)` | 进入（快出慢停） |
| `--e-accel` | `cubic-bezier(.3, 0, .8, .15)` | 退出（慢起快走） |

## 3. 每类交互的动效

| 交互 | 做法 |
|---|---|
| 按压 | 缩放到 0.97（图标按钮 0.9）；主按钮、发送键、新会话按钮加水波 |
| 开关、分段选择 | 开关的圆钮滑动；分段控件下方有一块滑块（`.segmented::before`，位置由 `syncSegment` 写入的 `--seg-n` / `--seg-i` 决定）平移到选中项 |
| 选中态 | 点下立即变化（前端先改，原生回推后确认） |
| 展开 / 收起 | 只对局部区域做高度动画：插入的折叠区用 `.fold` + `fx-unfold`（Grid 行高 0fr → 1fr），常驻折叠区切换 `.is-closed`，`<details>` 用 `::details-content` + `interpolate-size`（旧内核直接展开） |
| 页面切换 | 页面栈：更深的页面从右侧整页推入，旧页面左移 22% 并压暗；返回时反向；同层（切换会话）交叉淡入。动画期间旧页面保留在原位、不可操作（`swapPage`） |
| 全屏编辑页 | 和页面推入相同，Back 弹出 |
| 底部面板 | 从底部整块滑入（减速），滑出（加速）；内容变化时原地更新并平滑过渡高度 |
| 对话框 | 遮罩淡入，卡片从 0.94 放大；退出更快 |
| 长按 | 只用来进入会话多选（不弹菜单）；选中项用强调色底和勾选圆点，勾选点弹出（`fx-pop`） |
| 列表增删、重排 | FLIP：会话列表重排或删除时其他项平滑补位；删除的项先淡出再收起高度 |
| 新消息 | 上浮淡入（`fx-rise`） |
| 数值变化 | 原地交叉淡入（`data-swap` / `swapIn`），例如统计数字、会话副标题、本轮设置 |
| 提示条 | 从上方滑入，滑出时加速 |

## 4. 规则

1. **同一页面不重建。** 收到新视图时用 `morph` 原地更新，保留节点、滚动位置、焦点、进行中的过渡和展开状态。只有路由或会话变化才换页面。
2. **只动 transform 和 opacity**，由 GPU 合成。例外只有局部的展开收起高度（Grid 行高、`<details>` 内容、删除项收起），而且只作用于那一块区域，不对整条时间线做布局动画。
3. **一次性动效用 `fx-*` 类。** 只加在新插入的节点上；`morph` 不会给已有节点重新加这些类，动画结束后自动移除。要退场动画的节点声明 `data-exit`（`fold` / `fade` / `toast` / `collapse`）。
4. **系统“移除动画”时**（Android 的“移除动画”或动画时长缩放关闭，WebView 会报告 `prefers-reduced-motion: reduce`）：不位移、不缩放，所有进出都改为 120–160ms 的淡入淡出；FLIP、水波、高度补间都跳过。
5. **首帧安全。** 任何元素在动画没有运行时（被跳过、晚一帧开始、系统关闭动画）都必须处在可接受的静止状态：压暗页面用的黑色遮罩默认 `opacity: 0`，只由动画升起；页面转场时只让上层页面变化，下层页面保持完全不透明（`fx-hold`），不同时淡两页；WebView 自身底色是不透明的 `--canvas`，没画好的帧露出的是页面底色而不是黑色。
6. **不跑常驻的 JS 动画循环。** 动效由 CSS 或一次性的 Web Animations 完成。
7. **触控目标至少 44px**，正文和输入框字号不低于 16px（避免 WebView 自动放大）。

## 5. 组件清单

| 组件 | 位置 |
|---|---|
| 按钮 `.btn`（`primary` / `ghost` / `danger` / `small`）、图标按钮 `.icon-btn` | `src/dom.js`、`styles.css` |
| 分段控件 `.segmented` + `syncSegment` | `src/dom.js` |
| 开关 `.switch` | `render.js` `switchControl` |
| 选择行 `.choice-row`、选择面板 `openChoice` | `src/overlays.js` |
| 底部面板 `.sheet`、对话框 `.modal-card`、提示条 `.toast` | `src/overlays.js`、`render.js` |
| 全屏编辑页 `openPanel` / `closePanel`，表单字段 `.text-input` / `.choice-field` / `.chip-toggle` | `src/overlays.js`、`src/editors.js` |
| 服务商列表 `.preset-row`、路线卡片 `.route-card`（展开协议细节与附加请求头 `.header-row`）、能力列表 `.cap-row`（值 + 来源标签 `.chip.src`）、危险操作区 `.danger-zone` | `src/editors.js` |
| 折叠区 `.fold` / `.fold-body` | `styles.css` |
