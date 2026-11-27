# Changelog / 更新日志

## v2.4 (2026-11-27)

### Fixed / 修复
- 🐞 **图片素材与项目封面无法显示**：frontend 与 exporter 未挂载对象存储目录，导致 nginx 的
  `/internal/assets` 指向 rootfs 内的空目录。现在三个组件统一挂载同一份 `objects` 目录。

## v2.3 (2026-11-27)

### Changed / 变更
- ❌ **移除「文件保存路径」功能**：该功能原为预留项（SAF 返回的 `content://` 虚拟 URI 无法
  映射为 POSIX 路径供 PostgreSQL / nginx 使用），实际只写不读。按用户反馈砍掉。
- ✨ 新增「数据存储」卡片：实时统计 App 私有目录占用（递归统计，跳过符号链接，限深 24 层）。

### Fixed / 修复
- 初版数据占用显示 37.86 GB —— 根因是遍历跟随了 rootfs 内的 `lib -> usr/lib` 符号链接导致重复计数。

## v2.2 (2026-11-27)

### Added / 新增
- ⚙ **设置页**（主界面左上角齿轮进入）
- 🔄 **检查更新**：直连 Docker Registry 查询 `penpotapp/frontend` 的全部 tag，语义化版本比对
- ⬇️ **下载并更新**：后台下载新版镜像到 staging → 停服 → 自动备份数据库 → 原子切换 → 自动重启
- 🔒 更新前自动备份 PostgreSQL 数据到 `files/backups/pg_<ver>_<ts>.tar.zst`（保留最近 3 份）

### Verified / 验证
- 完整升级链路真机验证：账号 / 团队 / 项目 / 文件全部保留，数据零丢失。

## v2.1 (2026-11-27)

### Fixed / 修复
- 🐞 **停止服务按钮无效**：`stopAll()` 只发 `SIGTERM`，而 loader 的信号兼容层会吞掉它。
  改为扫描 `/proc` 按 uid 精确强杀（`/system/bin/kill -9`，因为 `Process.killProcess()`
  对孤儿进程在本机 ROM 上无效）。
- 🐞 **停止后日志不更新**：日志轮询的续跑条件绑定在 `serviceRunning` 上，停止瞬间即停摆。
  改为与 Activity 生命周期（`onResume`/`onPause`）绑定。
- 🐞 **停止后按钮不高亮**：`serviceRunning` 只是 Activity 局部变量。状态机移至
  `PenpotService.STATE`，Activity 每次轮询同步。

### Changed / 变更
- 移除 `android:debuggable`，作为正式版分发。

## v2.0 (2026-11-27)

### Added / 新增
- 🎨 全新深色科技风 UI（状态仪表盘 / 渐变按钮 / 终端日志卡 / 深色主题）
- 🖼 全新应用图标（等距线描「打开的设计盒」，全密度适配）

## v1.0 (2026-11-26)

### Added / 新增
- 🎉 首个可用版本：Android 上以用户态运行完整 Penpot（PostgreSQL / Valkey / Backend / Frontend / Exporter）
- 一键启动、局域网访问提示、五大组件生命周期管理
