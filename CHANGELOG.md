# 更新记录

## v0.0.5 — 2026-09-06

基于 v0.0.4（`eb8e52e786b95e04aadb2a64f1a9e24ee079729f`）的修复版本，Android versionCode 升至 5，SQLite schema 保持 15。

- 修复用量漏计：Router、Checkpoint、Web planner、verifier、repair、复核请求与子任务均纳入回合统计；失败、超时和取消保留已经报告的用量。同一请求的累计回调不会重复计算，缺失用量明确标记为未知或部分已报告。
- 修复导入资源边界：在流式 JSON 解析前限制单个原始 token 为 1 Mi 字符、单会话 JSON 为 8 Mi 字符、嵌套 128 层；节点超限立即停止，已完成的会话保留并报告。保留已有格式清洗、分支选择和总文件容量限制。
- 修复图片资源边界：写入前限制 16,777,216 像素、单边 8,192 像素，保留 12 MiB 文件限制；对模型图片返回值增加 Base64 长度检查。
- 修正 Anthropic 真实端点 smoke 的默认 API 根路径为 `/v1`；补齐 `web_planning` 与用量字段契约。
- 更新当前开发说明；v0.0.4 项目审查文档和 v0.0.2 上下文设计保留为历史基线。

本次对应已核实的 R2、R4、R9、D1、D2 和文档漂移；未扩大工具调用上限、改变开发方向、引入新依赖或调整 Provider/Model/Session 架构。

### 验证

- `npm run build` 静态架构与 JavaScript 检查通过，包括取消回合用量不覆盖上一条回复的回归检查。
- 从 `:app:clean` 开始，执行 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`，完整构建通过。
- 32 个测试类、231 项测试：228 通过，0 失败，0 错误；3 项真实端点 smoke 按凭据开关跳过，未验证外部服务。
- Debug Lint 为 0 错误、5 项现有配置/依赖提示；Release Lint/R8/资源优化通过。两份 APK 元数据均为 versionName `0.0.5`、versionCode `5`。
- 最终源码与差异已单独只读复核，`git diff --check` 通过。
- 本机未连接 Android 设备，设备测试未执行。Release APK 为未签名产物。

本地产物归档在 `build/releases/v0.0.5/`（Git 忽略目录），含 Debug APK、未签名 Release APK、SHA256SUMS 和完整构建日志。
