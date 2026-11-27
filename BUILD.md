# 🔧 Build from source / 从源码构建

> 本项目没有使用 Android Studio / Gradle，而是直接用 `aapt + d8 + apksigner` 的手工构建管线，
> 因为整套流程可以在一台 Android 手机上的 Termux 类环境里跑通。下方命令按 Linux/macOS 的路径写，
> 移植到手机时把路径换成实际路径即可。

## 依赖 / Dependencies

| 工具 | 用途 |
|---|---|
| JDK 17 | 编译 Java 源码（`javac`）、生成签名密钥（`keytool`） |
| Android SDK Build-Tools | `aapt` / `d8` / `apksigner` / `zipalign` |
| `android.jar` | 编译期 Android 平台 API 类路径（任选一个 API 24+ 的 `android.jar`） |
| `zip` / `unzip` | 往 APK 里追加 `classes.dex` |
| zstd / tar | 制作 `assets/base.tar.zst` |

## 目录结构

```
.
├── AndroidManifest.xml          # 应用清单
├── build.sh                     # 一键构建脚本
├── src/com/penpot/android/      # 全部 Java 源码（13 个类）
├── res/                         # 图标与主题资源
├── assets/
│   ├── dsh_alpine               # 静态 ELF 用户态装载器（43 KB）
│   ├── zstd                     # zstd 解压器（bionic 链接，供解包用）
│   ├── libzstd.so.1 / libz.so.1 / liblzma.so.5   # zstd 的三个动态依赖
│   ├── base.tar.zst             # 精简 Alpine rootfs（含 PG / Valkey / shim）
│   └── templates/               # BE / FE / EX 启动脚本与配置模板
├── docs/images/                 # README 截图
└── penpot.keystore              # 构建时自动生成（首次构建）
```

## 构建步骤

```bash
# 1. 准备环境变量（按你的实际路径修改）
export JDK=/path/to/jdk-17
export BT=/path/to/android-sdk/build-tools/34.0.0
export AJAR=/path/to/android.jar
export ZIP=/usr/bin/zip
export UNZIP=/usr/bin/unzip

# 2. 一键构建
bash build.sh
# 产物：penpot.apk
```

`build.sh` 的六个步骤：

1. `javac -source 8 -target 8` 编译 `src/com/penpot/android/*.java` → `classes/`
2. `d8 --lib android.jar --output classes $(find classes -name '*.class')` → `classes.dex`
   （**必须把全部 class 都喂进去**，包括 `MainActivity$1.class` 这类匿名内部类，否则运行时会 `NoClassDefFoundError`）
3. `aapt package -f -M AndroidManifest.xml -S res -I framework-res.apk -A assets -F unsigned.apk`
   （`-S res` 不能漏，否则图标与主题不会被编译）
4. 把 `classes.dex` 用 `zip` 追加进 `unsigned.apk`
5. `zipalign -f -p 4` 对齐 → `apksigner sign` 签名（首次自动用 `keytool` 生成 `penpot.keystore`）
6. `apksigner verify` 校验

## 关键资产怎么来的

### `assets/dsh_alpine`（用户态装载器）

43 KB 的静态 ELF（aarch64），无 `PT_INTERP`、无 `PT_DYNAMIC`，用法：

```
dsh_alpine [-m guest:host]... <rootfs> <interp> <guest> [args...]
```

它把 `rootfs` 作为根、用 `<interp>` 启动 `<guest>`，可选 `-m` 挂载额外目录。
`-m` 是本项目对象存储共享的关键（FE / BE / EX 三组件都要挂同一个 `objects` 目录）。

### `assets/base.tar.zst`（精简 Alpine）

这是从完整 Alpine 中裁剪出来的最小可运行集合：

- `postgresql17`（`/usr/libexec/postgresql17`、`/usr/share/postgresql17`、`/usr/lib/postgresql17`）
- `valkey-server`
- musl libc / ld-musl
- openssl（`libcrypto.so.3` / `libssl.so.3`）
- ICU 数据 + zoneinfo（缺了会 `could not open collator for locale "und"`）
- `busybox` 以及 `busybox --install -s /bin` 建立的全套 applet 软链
- `opt/shim/` 下的三个 `LD_PRELOAD` 垫片 + 启动脚本

打包命令：

```bash
tar --format=ustar -cf - -C base-min . | zstd -q -o assets/base.tar.zst
```

> ⚠️ 用 `tar --format=ustar` 而不是 GNU 格式，避免 PAX 扩展头让自研解析器出错。

### `assets/templates/`（组件配置）

| 路径 | 说明 |
|---|---|
| `be/start.sh` | Backend 启动：`JAVA_HOME`、`LD_PRELOAD=libacceptshim.so`、`-Djdk.lang.Process.launchMechanism=FORK`、DB/Redis URI |
| `fe/start.sh` | Frontend 启动：`LD_PRELOAD=libacceptshim.so`、`exec nginx -g "daemon off;"` |
| `fe/nginx.conf` | 反向代理配置（`listen 3449`、`/api` → 6060、`/internal/assets` alias 到对象存储） |
| `fe/resolvers.conf` / `resolv.conf` | DNS 解析配置 |
| `ex/start.sh` | Exporter 启动：node + Chromium `--single-process` |
| `ex/chrome-wrapper` / `headless-wrapper` | Chromium 启动包装（注入单进程参数） |
| `libacceptshim.so` | `accept()` → `accept4()` 垫片（glibc 版，供 BE/FE/EX 使用） |

这些模板在镜像解压后由 `DownloadCoordinator.postProcess()` 覆写进各组件 rootfs，
并把 `PENPOT_PUBLIC_URI` 替换为运行时探测到的局域网 IP。

## 运行期依赖

App 首次启动会从 Docker Hub 拉取三个镜像（arm64）：

- `penpotapp/frontend:2.19.5`（约 295 MB）
- `penpotapp/backend:2.19.5`（约 406 MB）
- `penpotapp/exporter:2.19.5`（约 695 MB）

下载与解压逻辑见 `RegistryClient.java` 和 `ImageExtractor.java`。

## 调试技巧

- **看 App 日志**：`android:debuggable="true"` 后
  `adb shell run-as com.penpot.android cat files/app.log`
- **看某组件日志**：同目录下 `pg.log` / `valkey.log` / `be.log` / `fe.log` / `ex.log`
- **确认挂载生效**：
  `adb shell ps -A -o pid,args | grep dsh-alpine`，应看到 `-m /opt/data/assets:<files>/objects`
- **端口探测别手算十六进制**：3449=`0x0D79`、6060=`0x17AC`、6061 在 `tcp6` 里

## 已知构建陷阱

| 陷阱 | 症状 | 解法 |
|---|---|---|
| `d8` 只喂顶层 class | `NoClassDefFoundError: MainActivity$1` | 用 `find ... -name '*.class'` 喂全部 |
| `aapt` 少了 `-S res` | 图标不显示、主题报找不到 | 加上 `-S res` |
| `GradientDrawable` 导入错包 | `cannot find symbol: class GradientDrawable` | 正确是 `android.graphics.drawable.GradientDrawable` |
| `tar` 用 GNU/Pax 格式 | 自研解析器路径被截断 | 用 `tar --format=ustar` |
| debuggable 版上架 | 应用商店/安全扫描告警 | 正式包移除 `android:debuggable` |
