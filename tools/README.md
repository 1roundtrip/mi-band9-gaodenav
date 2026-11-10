# tools/ —— 构建辅助脚本

这套脚本把「找依赖、配环境、做签名、打两个包」这些容易出错的步骤固定下来，
目标是：**新克隆仓库的人只需要 3 条命令就能出可发布的 APK + rpk**。

| 脚本 | 作用 | 需要 |
| --- | --- | --- |
| `fetch-deps.sh` / `fetch-deps.ps1` | 下载高德导航 SDK（236MB）与小米互联 AAR，整理成固定目录结构 | `curl` + `unzip`（PS 版用 curl.exe + .NET） |
| `check-env.sh` | 分别检查 APK 与 RPK 两条链路的工具链/依赖/签名是否齐全 | 无 |
| `gen-signing.sh` / `gen-signing.ps1` | 生成 keystore，导出 `private.pem`/`certificate.pem`，并分发到两个手环工程 | JDK + `openssl` |
| `verify-rpk-cert.py` | 校验 rpk 里内嵌的证书是不是你的（防止 aiot 静默用自带证书） | Python 3 |
| `build-all.sh` | 一键：检查 → 依赖 → 签名 → APK → 手环 9 / 9 Pro rpk → `dist/` 汇总报告 | 上面全部 |
| `lib/detect.sh` | 公共探测函数（android.jar / build-tools / dexer），被 `build.sh` 与 `check-env.sh` source | 无 |

## 典型流程

```bash
bash tools/check-env.sh                  # 0) 先看缺什么
bash tools/fetch-deps.sh                 # 1) 下依赖（高德 + 小米互联）
export KS_PASS='你的密码'
bash tools/gen-signing.sh                # 2) 统一签名（APK 与 rpk 必须同一把）
bash tools/build-all.sh                  # 3) 一键出包
```

只打 APK：`KS_PASS='密码' bash android-app/build.sh`（先跑 `bash android-app/build.sh --check` 可只做检查）。

## 依赖落地位置

```
third_party/AMapNavi/
├── libs/<AMap3DMap+AMapNavi+AMapSearch+AMapLocation 合并 jar>
├── assets/                       高德地图/导航资源（从 jar 的 assets/ 抽出，约 48MB）
└── jni/arm64-v8a/*.so            7 个 arm64 so（约 87MB）
third_party/r8/r8.jar             仅 --with-r8 时下载（本机没有 d8 时用它出 dex）
android-app/libs/xms-wearable-classes.jar   从 BandQQ 的 AAR 解出的 classes.jar
```

这些目录都在 `.gitignore` 里：**依赖文件不进仓库**（高德 SDK 与小米互联 SDK 都不是本项目的代码，
许可证不允许转分发；一并也让仓库保持 1MB 级）。

## 常用选项

```bash
# 依赖
bash tools/fetch-deps.sh --check                    # 只检查依赖是否齐
bash tools/fetch-deps.sh --offline ~/下载/AMap_Android_Navi_SDK_All.zip
bash tools/fetch-deps.sh --with-r8                  # 没有 d8 时下载 R8
bash tools/fetch-deps.sh --skip-amap --force        # 只重下小米互联

# 签名（已有 keystore 时只导出 PEM）
bash tools/gen-signing.sh --from ~/keys/release.jks

# 一键构建
bash tools/build-all.sh --skip-rpk                  # 只打 APK
bash tools/build-all.sh --skip-apk                  # 只打 rpk
bash tools/build-all.sh --no-fetch                  # 完全离线（依赖必须已在本地）
bash tools/build-all.sh --clean                     # 先清产物
```

## Windows

- 依赖下载与签名有 PowerShell 版：
  ```powershell
  powershell -ExecutionPolicy Bypass -File tools\fetch-deps.ps1
  powershell -ExecutionPolicy Bypass -File tools\gen-signing.ps1 -Pass '你的密码'
  ```
- APK 打包（`android-app/build.sh`）是 bash 脚本：在 **WSL2** 或 **Git Bash** 里跑，
  并确保 `ANDROID_HOME` 指向 Windows 版 Android SDK（build-tools 里带 `aapt.exe`/`apksigner.bat`/`d8.bat`）。
  `build.sh` 会自动识别 `.bat` 形式工具并把 build-tools 加入 PATH。

## 依赖下载失败怎么办

1. 高德 SDK 直链下载失败：浏览器手动下载
   <https://amappc.cn-hangzhou.oss-pub.aliyun-inc.com/lbs/static/zip/AMap_Android_Navi_SDK_All.zip>
   然后 `bash tools/fetch-deps.sh --offline <下载路径>`。
2. 小米互联 AAR 取不到（GitHub 被墙）：设镜像前缀，例如
   `XMS_PROXY=https://gh-proxy.com/ bash tools/fetch-deps.sh`；
   或手动下载 <https://github.com/Gsjsjzhznsz/BandQQ/blob/main/android-sync/app/libs/xms-wearable-lib_1.4_release.aar>
   放到 `.cache/deps/xms-wearable-lib_1.4_release.aar`。
3. 公司网络需要代理：`export https_proxy=http://127.0.0.1:7890` 后重跑。
