# 小米手环 9 / 9NFC 高德导航（手环导航）

把高德导航的转向提示推到手腕：**手机端 App（Android）+ 手环端 Vela 快应用（rpk）**，经小米互联（xms-wearable）通道传输。

- 手机端用高德**官方导航 SDK** 算路与导航，监听 `AMapNaviListener.onNaviInfoUpdate(NaviInfo)` 拿结构化引导数据（转向图标类型、下一路口距离、剩余里程/时间）
- 数据打包成 JSON 帧，通过小米互联 SDK 发送到手环
- 手环端快应用逐帧渲染：转向图标 + 动作文字 + 下一路口距离 + 剩余时间/里程，转弯时震动

> **作者：往返** · 本项目为个人学习作品，非官方。使用前请阅读发布包内的《免责声明》，许可见文末 `LICENSE`。
> 仓库**不包含**高德 SDK 和小米互联 SDK（都不是本项目代码，许可证不允许转分发），由 `tools/fetch-deps.sh` 自动获取并按固定目录摆放。

---

## 功能特性

- **导航提示**：转向图标（左转/右转/直行/左前/右前/左后/右后/掉头/环岛/到达）、动作文字、下一路口距离、剩余时间与里程
- **转弯震动**：仅在转向动作变化时震动一次（距离倒数不震）
- **三种方式**：驾车 / 骑行 / 步行（对应 `calculateDriveRoute` / `calculateRideRoute` / `calculateWalkRoute`）
- **目的地选择**：
  - 关键词搜索 → **候选列表**（名称 + 详细地址 + 距离），点选后确认，不会自动取第一条
  - 地图选点（3D 地图点击落点 + 逆地理地址）
  - 坐标直输（如 `39.9087,116.3975`）
- **模拟导航**：仅测试用，按路线自动行驶（默认关闭）
- **退出即停**：退出导航页 / 返回键 / 到达目的地都会真正 `stopNavi` 并停发帧
- **深色界面**：状态卡片（手环连接/已下发帧数/导航状态）+ 高德 Key 配置卡片
- **运行时 Key**：高德 Key **不内置**在安装包里，首次使用在 App 主界面填写并保存在本机；启动导航时通过 `setApiKey` 应用到地图/导航/定位/搜索四个模块

---

## 快速开始

> 需要：JDK 17+、Android SDK（build-tools + 任一 platform）、Node.js 18+、`unzip`/`curl`。
> Linux / macOS / WSL / Windows Git Bash 均可构建（见下文「构建环境」）。

```bash
# 0) 环境自检（缺什么、怎么装，脚本会直接告诉你）
bash tools/check-env.sh

# 1) 获取第三方依赖：高德导航 SDK（约 236MB 下载）+ 小米互联 AAR（首次约 3~6 分钟）
bash tools/fetch-deps.sh

# 2) 生成统一签名（APK 与 rpk 必须是同一把 keystore，否则互联报签名不一致）
export KS_PASS='你的密码'
bash tools/gen-signing.sh

# 3) 一键构建：APK + 手环 9 + 手环 9 Pro 两个 rpk
bash tools/build-all.sh        # --skip-rpk 只打 APK；--skip-apk 只打 rpk
```

产物在 `dist/`：`cn.dsh.gaodenav.apk`、`手环导航-手环9.rpk`、`手环导航-手环9Pro.rpk`、`BUILD-REPORT.txt`（哈希/证书/版本）。

只想打 APK 或只想打 rpk 时：

```bash
bash android-app/build.sh --check      # 只检查 APK 构建环境与依赖，不构建
KS_PASS='你的密码' bash android-app/build.sh    # 只打 APK（产物在 android-app/dist/）
(cd band-app && npm install && npx aiot release)      # 只打手环 9 的 rpk
(cd band-app-pro && npm install && npx aiot release)  # 只打手环 9 Pro 的 rpk
python3 tools/verify-rpk-cert.py band-app/dist/<你的>.rpk keys/certificate.pem   # 校验 rpk 内嵌证书
```

---

## 目录结构

```
.
├── README.md  SIGNING.md  LICENSE  .gitignore
├── tools/                          # 构建辅助脚本（依赖/环境/签名/一键构建）
│   ├── fetch-deps.sh   fetch-deps.ps1      高德 SDK + 小米互联 AAR + （可选）R8
│   ├── check-env.sh                         APK / RPK 两条链路的环境自检
│   ├── gen-signing.sh  gen-signing.ps1      keystore -> PEM，并分发到两个手环工程
│   ├── verify-rpk-cert.py                   校验 rpk 里内嵌的证书是不是你的
│   ├── build-all.sh                         检查 + 依赖 + 签名 + APK + rpk 一条龙
│   └── lib/detect.sh                        android.jar / build-tools / dexer 探测
├── android-app/                    # 手机端 App（Java，手工构建，不依赖 Gradle）
│   ├── AndroidManifest.xml
│   ├── build.sh                    # javac + D8/R8 + aapt + zipalign + apksigner 流水线
│   ├── tools/IconMapperTest.java   # 图标映射离线自测（build.sh 第 1 步会跑）
│   ├── libs/                       # 放 xms-wearable-classes.jar（fetch-deps 生成，不进仓库）
│   ├── res/                        # 图标与字符串
│   └── src/cn/dsh/gaodenav/
│       ├── MainActivity.java        # 主界面（连接手环 / 开始导航 / 高德 Key 配置）
│       ├── NavSdkActivity.java      # 高德导航页（AMapNaviView + 目的地选择 + 导航帧）
│       ├── MapPickActivity.java     # 地图选点（MapView + 逆地理）
│       ├── NaviCallbackAdapter.java # AMapNaviListener / AMapNaviViewListener 适配
│       ├── NaviIconMapper.java      # 高德 IconType -> 手环 turn/中文动作
│       ├── NavFrame.java            # 手环 JSON 帧
│       ├── Bridge.java              # 小米互联（反射调用 xms-wearable）
│       ├── AmapPrivacy.java         # 高德隐私合规统一入口
│       ├── AmapKey.java             # 高德 Key 运行时管理（SharedPreferences + 四模块 setApiKey）
│       └── Repo.java                # 全局状态与日志环形缓冲
├── band-app/                       # 手环端 Vela 快应用（手环 9 / 9NFC，designWidth 192）
│   ├── package.json
│   ├── src/                        # manifest.json / pages/index / icons
│   └── sign/debug|release/         # 放 private.pem + certificate.pem（不进仓库）
├── band-app-pro/                   # 手环 9 Pro（designWidth 336）
├── third_party/AMapNavi/           # 高德 SDK 落地目录（fetch-deps 生成，不进仓库）
└── keys/                           # 签名库与 PEM（gen-signing 生成，不进仓库）
```

---

## 工作原理

```
┌────────────────────────── 手机（Android 8+）──────────────────────────┐
│  高德导航 SDK                                                          │
│    AMapNaviView（地图/导航界面） + AMapNavi（算路/导航状态）             │
│         │                                                              │
│         │ AMapNaviListener.onNaviInfoUpdate(NaviInfo)  约 1 次/秒       │
│         ▼                                                              │
│  NaviIconMapper（getIconType -> turn/动作文字）                         │
│  NavFrame（distMeters / remainMeters / remainSeconds / vibrate / seq）  │
│         │                                                              │
│         ▼                                                              │
│  Bridge（小米互联 SDK：connectedNodes -> 授权 -> launchWearApp ->       │
│          addListener -> sendMessage(json bytes)）                      │
└────────────────────────────────┬───────────────────────────────────────┘
                                 │ 小米互联通道（经小米运动健康 / 穿戴服务）
                                 ▼
┌────────────────────────── 手环（Vela 快应用）──────────────────────────┐
│  @system.interconnect  onmessage -> JSON.parse                          │
│  渲染：转向图标 + 动作文字 + 距离 + 剩余时间/里程；seq 增大且 vibrate    │
│  时震动一次；收到 {"type":"stop"} 恢复待机界面                          │
└────────────────────────────────────────────────────────────────────────┘
```

### 手环端 JSON 帧协议

```json
{"type":"nav","seq":12,"turn":"left","action":"左转",
 "distMeters":300,"remainSeconds":720,"remainMeters":3200,"vibrate":true}
{"type":"stop"}
```

- `turn`：`left | right | straight | slight-left | slight-right | uturn-left | uturn-right | arrive`
- 未知字段可省略（手环端对 undefined 不显示）
- `seq` 递增；`vibrate=false` 不震动

---

## 前置依赖

| 依赖 | 说明 | 获取方式 |
| --- | --- | --- |
| Android 8+ 小米手机 | 需要安装「小米运动健康」并已连接手环 | 用户自备 |
| 小米手环 9 / 9NFC / 9 Pro | 手环端为 Vela 快应用（rpk） | 用户自备 |
| 高德开放平台 Key | 服务类型「Android 平台 SDK」，绑定**包名 + SHA1**（见 SIGNING.md） | <https://lbs.amap.com/> 免费申请 |
| 高德 Android 导航 SDK | 官方直链：<https://amappc.cn-hangzhou.oss-pub.aliyun-inc.com/lbs/static/zip/AMap_Android_Navi_SDK_All.zip> | **自动**：`bash tools/fetch-deps.sh` |
| 小米互联 SDK | 取自开源项目 BandQQ：<https://github.com/Gsjsjzhznsz/BandQQ>，只取 `android-sync/app/libs/xms-wearable-lib_1.4_release.aar` 里的 `classes.jar` | **自动**：`bash tools/fetch-deps.sh` |
| 手环端打包工具 | `aiot-toolkit`（npm 全局或工程内安装），用于 `aiot build` / `aiot release` | `npm i -g aiot-toolkit` 或 `npm install`（工程内会自动装） |
| 手环安装工具 | 表盘自定义工具（米坛），用于安装 rpk | 用户自备 |
| 构建工具链 | JDK 17+、`aapt`、`zipalign`、`apksigner`、D8/R8（无 Gradle）、`unzip`、`curl` | 见「构建环境」 |

`tools/fetch-deps.sh` 会把依赖整理成固定布局（`third_party/AMapNavi/{libs,assets,jni/arm64-v8a}`、
`android-app/libs/xms-wearable-classes.jar`），`build.sh` 直接按这个布局读取，不需要手工摆放。
下载失败时可手动下载 zip 后用 `--offline <zip>` 指定；GitHub 直连不通时会自动尝试镜像。

---

## 构建环境

### Linux / macOS / WSL

```bash
# Debian / Ubuntu
sudo apt install -y openjdk-17-jdk-headless unzip curl \
    aapt apksigner zipalign android-sdk-platform-23
# d8 没随发行版提供时（二选一）：
sudo apt install -y android-sdk-build-tools      # 提供 d8
bash tools/fetch-deps.sh --with-r8              # 或者下载纯 Java 的 R8

# macOS（Homebrew）
brew install openjdk@17 unzip
brew install --cask android-commandlinetools
sdkmanager "build-tools;34.0.0" "platforms;android-30"
```

没有 Android SDK 时也可以用 Android Studio 的 SDK Manager 装 build-tools 与任一 platform，
脚本会自动在 `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `~/Android/Sdk` 等常见位置查找
`android.jar` 与 build-tools；也可显式指定 `ANDROID_JAR` / `ANDROID_BUILD_TOOLS`。

### Windows

两种方式：

1. **WSL2（推荐）**：在 WSL 里按上面的 Ubuntu 步骤装依赖，然后 `bash tools/build-all.sh`。
2. **Git Bash + Windows 版 Android SDK**：装 JDK 17、Git for Windows、Android SDK（build-tools 34 + 任一 platform），
   把 `%ANDROID_HOME%` 指向 SDK 目录，在 Git Bash 里 `export ANDROID_HOME=...` 后执行 `bash tools/check-env.sh`。
   依赖下载也可以用 PowerShell 版：`powershell -ExecutionPolicy Bypass -File tools\fetch-deps.ps1`；
   签名同理：`powershell -ExecutionPolicy Bypass -File tools\gen-signing.ps1 -Pass '你的密码'`。
   （`build.sh` 会自动识别 `.bat` 形式的 `apksigner`/`d8`，并把 build-tools 目录加进 PATH。）

### 构建参数（`android-app/build.sh`）

| 变量 | 含义 |
| --- | --- |
| `KS_PASS` | keystore 密码（**必填**，不要提交到仓库） |
| `KS_FILE` | 签名库路径（默认 `keys/release.jks`） |
| `KS_ALIAS` | keystore 别名（默认 `gaodenav`） |
| `CERT_DIR` | `certificate.pem` 所在目录（默认 `keys/`，用于校验 APK 与 rpk 证书一致） |
| `ANDROID_JAR` | `android.jar` 路径（默认自动探测） |
| `ANDROID_BUILD_TOOLS` | build-tools 目录（默认自动探测，找到后自动加入 PATH） |
| `SDK_ROOT` | 高德 SDK 目录（默认 `third_party/AMapNavi`） |
| `XMS_JAR` | 小米互联 `classes.jar`（默认 `android-app/libs/xms-wearable-classes.jar`） |
| `BUILD_DIR` | 中间产物目录（默认 `${TMPDIR:-/tmp}/gaodenav-build`） |
| `DIST_DIR` | 产物输出目录（默认 `android-app/dist`） |
| `SDCARD_DEST` | 可选：构建后额外复制到的目录（如手机共享目录），不设置则不复制 |
| `ICON_SRC` | 可选：图标源目录（含 `mipmap-*` 五档 PNG），不设置则用仓库里的图标 |

`build.sh` 依次执行：
0. 环境与依赖检查（工具链、SDK、签名、清单回归防护）
1. `IconMapperTest` 图标映射离线自测
2. `javac -source 8 -target 8`（classpath：`android.jar` + 高德 SDK jar + 小米互联 `classes.jar`）
3. `jar` + **D8/R8** 生成 `classes.dex`（含高德与互联 SDK 的类）
4. `aapt package` 打资源与清单，`-A` 合并高德 SDK 的 48MB assets
5. `aapt add` 放入 `classes.dex` 与 `lib/arm64-v8a/*.so`
6. `zipalign -p -f 4`（`.so` 页对齐）
7. `apksigner sign`（v1/v2/v3）
8. 校验：APK 证书 SHA-256 必须等于 rpk 证书 SHA-256；`classes.dex`/so/assets 必须存在
9. 产出到 `DIST_DIR`，并打印 sha256 与工具链版本

> 构建前请确认清单里保留了 `QUERY_ALL_PACKAGES`：小米互联 SDK 依赖包可见性发现「小米运动健康/穿戴服务」，Android 11+ 删掉会导致手环连接失败（`build.sh` 已内置构建期校验）。

---

## 签名（APK 与 rpk 必须同证书）

```bash
export KS_PASS='你的密码'
bash tools/gen-signing.sh            # keystore -> PEM，并自动分发到两个手环工程
```

已有现成 keystore（不想新建）时只导出 PEM / 指纹：

```bash
KS_PASS='你的密码' bash tools/gen-signing.sh --from ~/keys/release.jks
```

详细原理、指纹查看、rpk 内嵌证书校验、aiot 静默回退自带证书的坑，见 **[SIGNING.md](SIGNING.md)**。

---

## 高德 Key 配置（运行时填写，不内置）

安装包里**不含 Key**（`AndroidManifest.xml` 的 `com.amap.api.v2.apikey` 为空占位），
用户首次打开 App，在「高德 Key」卡片粘贴并保存，App 保存到本机
`SharedPreferences`（`app_prefs` / `amap_key`），进入导航前依次调用：

```java
MapsInitializer.setApiKey(key);                 // 地图/导航
AMapLocationClient.setApiKey(key);              // 定位
ServiceSettings.getInstance().setApiKey(key);   // 搜索
AMapNavi.setApiKey(context, key);               // 导航
```

- 调用顺序：隐私合规（`AmapPrivacy.init`）→ `AmapKey.apply()` → 任何 SDK 初始化
- Key 必须绑定：服务类型「Android 平台 SDK」+ **包名** + **SHA1**（带冒号大写格式，用 `keys/fingerprints.txt` 里的 SHA1）
- 改 Key 后等 5~10 分钟生效；改包名/换签名都必须同步改 Key
- 未配置 Key 时，「开始导航 / 地图选点」会提示先回主界面填写
- 报 `INVALID_USER_SCODE` / code=22 基本都是 Key 绑定不匹配（见常见问题）

### 包名

默认 `cn.dsh.gaodenav`，**App 与 rpk 必须一致**：

- APK：`AndroidManifest.xml` 的 `package`
- RPK：`band-app/src/manifest.json` 的 `package`
- 高德 Key：绑定同一个包名 + APK 签名 SHA1

改包名时三处一起改，并重新申请/修改 Key。

---

## 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| Windows 下 `aapt dump badging` 报 `Illegal byte sequence` | aapt 打不开含中文/非 ASCII 的**绝对路径**。把仓库放在纯 ASCII 路径，或先 `cd` 到目录里再用相对文件名（`build.sh` 本身不受影响，它用相对/转换后的路径调用） |
| `build.sh` 报「找不到高德导航 SDK jar / assets / so」 | 依赖没准备好：`bash tools/fetch-deps.sh`（或手动下载后用 `--offline` 指定） |
| 报「找不到 android.jar」 | 没装 Android SDK platform：`sdkmanager 'platforms;android-30'`，或 `apt install android-sdk-platform-23`；也可 `export ANDROID_JAR=...` |
| 报「找不到 dexer（d8 / R8）」 | `sdkmanager 'build-tools;34.0.0'`（自带 d8），或 `bash tools/fetch-deps.sh --with-r8` |
| 报「证书不一致！APK=... rpk=...」 | 两端不是同一把 keystore：`bash tools/gen-signing.sh` 重新分发 PEM，两边都重新打包；手环上要先卸载旧 rpk（旧证书不能覆盖升级） |
| `aiot` 打包时日志里 `privatekeyPath` 指向 `aiotpack/lib/.../pem/private.pem` | aiot 没找到工程里的 `sign/release/`，静默用了自带证书：确认 PEM 存在后重打，并用 `tools/verify-rpk-cert.py` 校验 |
| 算路失败、日志 `code=22` / `INVALID_USER_SCODE` | 高德 Key 绑定的 **包名/SHA1** 与安装包不一致。核对 PackageName=`cn.dsh.gaodenav`、两栏都填 APK 签名证书的带冒号大写 SHA1；release 包只看「发布版安全码 SHA1」那栏；改完等 5~10 分钟 |
| 手环收不到帧（`已下发 N 帧` 增长但 `成功 M 帧` 不涨） | 互联未就绪：确认小米运动健康已连手环、已授权本 App、手环端 rpk 已安装并打开；先回主界面点「连接手环」 |
| 连不上手环、`未发现手环` | ① 手机需在小米运动健康里连着手环；② 清单**不能删** `QUERY_ALL_PACKAGES`（包可见性）；③ 小米互联要求同一个 App 首次连接时在运动健康里授权 |
| 提示「签名不一致」（手环互联报 fingerprint verify failed） | APK 与 rpk 证书不一致：两端用同一 keystore 重新签名，并在手环上卸载旧 rpk 后重装 |
| 搜索不到地点 | 搜索需要网络与有效 Key；换个关键词或直接用「地图选点」/ 坐标输入 |
| 地图白屏/黑屏 | Key 未配置/未生效，或 SDK 初始化中（等几秒）；先确认 App 里 Key 显示「已配置 ✓」并已过生效时间 |
| 定位一直「等待定位…」 | 真实导航需要新鲜定位：检查定位权限、系统定位开关、到开阔处 |
| 退出导航后手环仍显示旧画面 | 退出会发 `stop`；若没更新，重开手环端快应用 |

---

## 第三方组件

| 组件 | 来源 | 用途 | 许可证 |
| --- | --- | --- | --- |
| 高德 Android 导航 SDK（3DMap + Navi + Search + Location） | 高德开放平台官方下载 | 算路、导航、地图、搜索、定位 | 高德开放平台服务条款（**不随本仓库分发**） |
| 小米互联 SDK（xms-wearable-lib 1.4） | [BandQQ](https://github.com/Gsjsjzhzsz/BandQQ) 的 AAR | 手机 <-> 手环互联 | 见 BandQQ 仓库许可证（**不随本仓库分发**，只取 `classes.jar`） |
| aiot-toolkit | <https://iot.mi.com/vela/quickapp/> | 手环快应用打包 | 小米 Vela 工具链许可 |

本仓库只包含本项目自己的代码（MIT）。依赖由 `tools/fetch-deps.sh` 在构建时下载，运行时/分发时请遵守各自许可。

---

## 免责声明

非官方作品，与小米、高德无关联。仅供个人学习交流，禁止商用/倒卖。刷入第三方应用到手腕设备有风险，请自行评估并备份。详见发布包内《免责声明》。

---

## 机型适配（手环 9 / 9 Pro）

| 机型 | 屏幕 | 手环端目录 | designWidth |
| --- | --- | --- | --- |
| 小米手环 9 / 9NFC | 192×490 | `band-app/` | 192 |
| 小米手环 9 Pro | 336×480 | `band-app-pro/` | 336 |

- 手机端 APK 两台通用，不需要改包名或重新申请 Key
- 手环端 rpk 需按机型分别构建/安装，但**必须用同一把 keystore 签名**（与 APK 一致）
- 其他小米 Vela 设备（手环 10、手表 S 系列等）可参照同样方式新增目录，改 `designWidth` 与布局尺寸后重打包

---

## 版本

| 组件 | 版本 |
| --- | --- |
| 手机端 APK | 1.1.0（versionCode 8） |
| 手环端 rpk（9 / 9 Pro） | 1.1.0（versionCode 3） |

两端版本号已统一为 **1.1.0**；包名与证书必须一致。
### 已发布签名（v1.1.0）

| 项 | 值 |
| --- | --- |
| 证书主体 | `CN=往返, O=往返, C=CN` |
| 证书 SHA-1（高德 Key 绑定用） | `1D:69:3D:83:99:08:25:4F:B4:A8:AF:73:8A:EE:10:9B:F2:E1:D1:94` |
| 证书 SHA-256 | `DF:9F:5E:E5:C7:3E:23:3A:0B:10:E1:5C:74:E3:D1:31:70:42:41:9C:D8:A7:74:FF:38:AB:34:5F:BA:D1:0C:F6` |

- v1.1.0 的 APK / rpk 用上面这张证书签名；与旧版 v1.0.0 证书不同，**不能覆盖升级**，安装前先卸载旧版。
- 发布包二进制不放进仓库（`.gitignore` 已忽略），从源码自行构建会得到**你自己的证书**，
  高德 Key 必须按你自己证书的 SHA1 重新申请（看 `keys/fingerprints.txt`）。

---

## License

MIT License

```
MIT License

Copyright (c) 2026 往返

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
