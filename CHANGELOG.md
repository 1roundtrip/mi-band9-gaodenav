# 更新日志

## [v1.1.0] GitHub 开源首发 · 2026-11-10

作者：**往返**

本版是 GitHub 上发布的第一版，重点是「版本统一 + 重新签名 + 构建链可用」。

- **版本号统一为 1.1.0**：手机端 APK `versionCode 8`、手环端 rpk `versionCode 3`
  （此前是 APK 1.0.0/7、rpk 1.1.0/2，两边对不上）。
- **作者署名**：往返（`LICENSE`、`README`）。
- **重新签名**：用新 keystore 重新签发 APK 与两个 rpk：
  - 证书主体：`CN=往返, O=往返, C=CN`
  - 证书 SHA-1（高德 Key 绑定用）：`1D:69:3D:83:99:08:25:4F:B4:A8:AF:73:8A:EE:10:9B:F2:E1:D1:94`
  - 证书 SHA-256：`DF:9F:5E:E5:C7:3E:23:3A:0B:10:E1:5C:74:E3:D1:31:70:42:41:9C:D8:A7:74:FF:38:AB:34:5F:BA:D1:0C:F6`
  - 与旧版 v1.0.0 证书不同 → **不能覆盖升级**，安装前先卸载旧版（手环端 rpk 也要先删）。
  - 高德 Key 必须按上面的新 SHA1 重新申请/修改，否则报 `code=22`。
- 发布资源：`cn.dsh.gaodenav.apk`、`手环导航-手环9.rpk`、`手环导航-手环9Pro.rpk`、`SHA256SUMS.txt`。
- 仓库内新增 `免责声明.md`；README 版本表、构建说明同步。

## [未发布] 补齐开源构建链 · 2026-11-10

本次只改「开源版工程的可构建性/可维护性」，App 与手环端的功能代码未变。

### 新增

- `tools/fetch-deps.sh` / `tools/fetch-deps.ps1`：自动下载并整理高德导航 SDK（约 236MB）与
  小米互联 AAR，含完整性校验、缓存、`--offline` 本地包、GitHub 镜像回退；产物布局固定为
  `third_party/AMapNavi/{libs,assets,jni/arm64-v8a}` 与 `android-app/libs/xms-wearable-classes.jar`。
- `tools/check-env.sh`：分别检查 APK / RPK 两条链路的工具链、依赖、签名，缺什么直接给安装命令。
- `tools/gen-signing.sh` / `tools/gen-signing.ps1`：keystore → PEM 一条命令，自动分发到
  `band-app` 与 `band-app-pro` 的 `sign/{debug,release}`，输出 SHA1/SHA256 指纹。
- `tools/verify-rpk-cert.py`：校验 rpk 里内嵌的证书是不是你的（防止 aiot 静默回退自带证书）。
- `tools/build-all.sh`：检查 → 依赖 → 签名 → APK → 两个机型 rpk → `dist/` 汇总报告（含 sha256）。
- `tools/lib/detect.sh`：`android.jar` / build-tools / dexer 自动探测（`build.sh` 与 `check-env.sh` 共用）。
- `android-app/tools/IconMapperTest.java`：**补回构建第 1 步引用的自测**。原开源包里这个文件缺失，
  `build.sh` 在第一步就必然失败。
- `.github/workflows/ci.yml`：bash 语法、图标映射自测、python 语法检查。

### 修复

- `android-app/build.sh`：
  - 去掉 `/tmp/build`、`/opt/r8`、`/root/workspace`、`tools/phase1.sh` 等环境硬编码与失效引用；
  - 高德 SDK 默认读 `third_party/AMapNavi`（同时兼容旧的 `unzip/nested_AMap3DMap_*` 布局）；
  - `android.jar`、build-tools、d8/R8 全部自动探测，支持 `ANDROID_JAR` / `ANDROID_BUILD_TOOLS` / `R8_JAR` 覆盖；
  - 新增 `--check`（只检查不构建）与 `--help`；
  - 去掉对 `python3` 的强依赖（APK 内容校验改用 `unzip -l`），`openssl` 缺失时可退回 python 算指纹；
  - 兼容 Windows Git Bash：自动识别 `apksigner.bat`/`d8.bat`、classpath 分隔符按平台切换；
  - 日志/错误提示重写，失败时明确指出「跑哪个脚本补什么」。
- `README.md`：改为「3 条命令快速开始」，补依赖获取、构建环境（Linux/macOS/WSL/Windows）、
  构建参数表、故障排查（依赖/证书/dexer/Key）、第三方组件许可说明、版本矩阵。
- `SIGNING.md` / `band-app/README.md` / `band-app-pro/README.md`：改成优先用 `tools/gen-signing.sh`，
  并加上 `tools/verify-rpk-cert.py` 的 hard check 步骤。
- `.gitignore`：忽略 `third_party/`、`.cache/`、`keys/`、`node_modules/`、`band-app*/dist/`、
  `android-app/{out,dist}/` 等。

### 验证

- Windows Git Bash（MSYS2）下实际跑过全流程：依赖获取 → 签名 → `build.sh`（APK，d8 与 R8 两条
  dexer 分支）→ `aiot release`（两个机型 rpk）→ `build-all.sh`，产出 APK 证书 SHA-256 与 rpk
  证书一致、`classes.dex`/so/assets 齐全。
- Linux 端没有本机环境实测，靠 `.github/workflows/ci.yml` 的 full-build（手动触发）覆盖；
  脚本里已经对 Debian 的 aapt / apksigner / zipalign、缺失 d8 时回退 R8 做了兼容。
- `IconMapperTest` 覆盖 287 项断言（全部 IconType 映射、未知类型返回 null、29~54 兜底、turn 枚举合法性）。
- SIGPIPE 回归验证：旧写法（`unzip -l | grep -q`）100 次里 88 次返回 141；新写法 200 次 0 失败；
  `build.sh` 连跑 3 次全部通过（第 5/9 步不再假失败）；`fetch-deps.sh --with-r8` 实跑通过。
- R8（CI full-build 路径）验证：在 PATH 里去掉 d8、只留 R8 的情况下 `build.sh` 走
  `dexer : R8 (third_party/r8/r8.jar)` 分支，成功产出 4.5MB classes.dex，证书一致、so/assets 齐全。

### 修复（补丁 2 · SIGPIPE / pipefail）

- `android-app/build.sh` 第 5/9 步：`unzip -l ... | grep -q ...` 在 `set -o pipefail` 下会被 SIGPIPE 打断
  （`grep -q` 命中后立即退出，`unzip` 还在往管道里写 → 管道返回 141），于是会「必报」
  `APK 里没有 classes.dex`。实测旧写法 100 次里有 88 次返回 141。改为**先把清单落到文件再 grep**。
- `tools/fetch-deps.sh`：小米互联校验（`xms_jar_ok` 与 `fetch_xms` 两处）和 R8 校验（`fetch_r8`）
  是同样的写法，脚本是 `set -euo pipefail`，即使下载完全正确也会 `die "结构不对"`；
  改用统一的 `jar_has_entry()` 帮助函数（先落临时文件再 grep），`--with-r8` 不再必挂。
- `tools/fetch-deps.sh --with-r8`：maven 的 `<latest>` 是 `9.6.0-alpha12`（alpha），
  改为优先挑最新**稳定版**，实测下载 `9.5.29`（21MB）且 `D8` 可正常执行。
- `tools/gen-signing.sh`：`sed ... | head -1` 同属 SIGPIPE 风险写法，改为 `sed -n '1p'`。
- `android-app/build.sh`：修掉一个既有 bug —— 探测 `android.jar` 前先把 `ANDROID_JAR` 变量清空，
  导致文档里写的 `export ANDROID_JAR=...` 覆盖方式失效（CI 那种「无 Android SDK 目录、只给
  android.jar 路径」的场景会直接报「找不到 android.jar」）。改为不覆盖用户传入的值。

### 修复（补丁 3 · 全仓审计）

- `SIGNING.md`：手工步骤里 keystore 别名写的是 `bandnav`，但 `tools/gen-signing.sh` 与
  `build.sh` 默认别名是 `gaodenav` —— 照文档手做出来的 keystore 会让构建签名直接失败。已统一为 `gaodenav`。
- `band-app/README.md`：manifest 示例的 `versionCode` 写 1，实际是 2。
- `android-app/AndroidManifest.xml` 注释：原来写死「包名不可改 + 原作者 SHA1」，容易让人误以为要沿用别人的
  证书；改为「绑定你自己的包名 + 你自己的 SHA1」。
- `android-app/build.sh`：
  - 老版本 aapt 不认 `--min-sdk-version/--target-sdk-version` 时自动回退（清单里已有 uses-sdk）；
  - `--check` 模式会明确提示「签名还没准备」；
  - `findAmapJar` 去掉 `ls | head`（pipefail 下的 141 风险）。
- `tools/build-all.sh`：`check-env` 失败时不再错误地 `die`（原来 `--skip-apk` 会被 APK 侧缺项拦住）；
  注释里 APK 大小 100MB+ 更正为 60~80MB。
- `tools/fetch-deps.sh`：R8 版本兜底解析改用 awk，避免 `sed | head` 同类风险。
- `tools/check-env.sh`：`ANDROID_BUILD_TOOLS` 指向不存在的目录时回退自动探测。
- `.github/workflows/ci.yml`：full-build 不再硬依赖 apt 的 `aapt`（Ubuntu 24.04 未必有该包），
  优先用 runner 预装 Android SDK、apt 只做 `|| true` 兜底；static job 新增「禁止 `unzip … | grep -q`
  / `| head`」守卫，防止补丁 2 的问题回归。
- `band-app*/package-lock.json`：749 处 `resolved` 原本指向本机 npm 镜像 `registry.npmmirror.com`,
  统一改回 `registry.npmjs.org`（同一 tarball，integrity 不变）。
- `tools/gen-signing.sh`：提示里的 `export KS_FILE=$KS` 没加引号，路径含空格会坏。

### 已知

- 源码版本：APK `1.0.0 (versionCode 7)`、rpk `1.1.0 (versionCode 2)`，与已发布包一致；两端版本号独立演进。
- `米坛发布版/使用说明.md` 里写的是原作者的 SHA1 与 v1.0.0；若你用自己的 keystore 重新打包并发布，
  必须同步改这份说明里的 SHA1 与版本号，否则用户填 Key 会报 `code=22`。
- 重新签名的包与已发布包签名不同：老用户无法覆盖升级，需先卸载（手环端 rpk 尤其）。
- 真机验证（运行时填 Key、算路、9 Pro 显示）仍未做；Linux 侧靠手动触发的 CI full-build 覆盖。
- 高德 SDK / 小米互联 SDK 不随仓库分发，首次构建需联网下载一次（之后走 `.cache/` 缓存）。
