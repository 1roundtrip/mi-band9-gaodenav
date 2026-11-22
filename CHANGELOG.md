# 更新日志

## [v2.1.0] 导航流程对齐高德 + 稳定性修复 · 2026-11-22

本版把「首页 → 搜索候选 → 选路 → 导航中 → 到达页」五屏的交互与信息布局按高德官方 App 对齐
（红米 K70 真机逐屏截图验收），并修复交接审计发现的问题。版本号升至 **2.1.0**
（手机端 APK versionCode 10，手环端 rpk versionCode 5；证书不变，可覆盖升级）。

### 功能与交互（对齐高德）
- **选路页**：绿点「我的位置」/ 红点「输入终点」起终点卡 + ⇅、文字出行方式 Tab、选定目的地自动算路、
  方案卡按用时排序（推荐·最快在前）、**路线偏好底部弹层**（智能推荐 / 高速优先 / 不走高速 / 躲避拥堵 / 少收费 / 时间优先，
  走 `PathPlanningStrategy` 常量真实生效）、**多方案对比气泡**（地图分叉处「慢X分钟 / 少Y公里 · 多N个红绿灯」，点气泡切换）、
  非选中方案自动置灰、镜头自动适配整条路线、驾车偏好入口
- **搜索结果**：蓝色加粗名称 + 类型·地址 + 距离（公里/米）+「去这里」，「搜索结果 · N 个」表头
- **导航中**：转向面板不再被手环角标遮挡（角标移到面板下方右侧）、退出二次确认；
  保持 SDK 原生车道线/路口放大/电子眼/路况与 TTS
- **到达页**：高德式底部白卡（用时/目的地/手环状态 + 重新规划 + 返回首页）
- **历史记录**（新增 `History`）：本地保存最近目的地与搜索词，首页显示「最近去过」、路线页显示「历史路线 / 历史搜索」；
  首页「清除」+ 长按删除单条，路线页两个分区各带「清空」+ 长按删除单条（都带确认弹窗，只清本机记录）；
  含链接的整段分享文本不再记成搜索词

### 修复
- 「开始导航」按钮在算路后被隐藏且从不恢复，导致无法开始导航（严重）
- 设置抽屉被底部卡片/搜索栏遮挡（FrameLayout 按 elevation 绘制，未抬高），到达页同样问题
- 系统分享入口不读 `EXTRA_TEXT`（交接声称的「分享到本应用」此前实际不生效）；导出入口补坐标/域名校验
- 裸文本坐标误判（`35,000`、`2026,11` 被当成坐标）；短链改为单次抓取（`resolveAny`）；定位等待可失败可重试
- 途经点重复 `&via=` 只取第一个；途经点名称含逗号错位

### 精简
- **移除未使用的 `WRITE_EXTERNAL_STORAGE` 权限**（App 不导出文件，高德 SDK 缓存写在应用私有目录），
  权限只剩：包可见性（小米互联必需）、网络/WiFi 状态、定位、WAKE_LOCK
- 移除仅供调试的 intent 开关（`debug_auto_nav` / `debug_pref` / `debug_show_pref` / `debug_arrive` / `debug_settings`），
  调试用的真机截图脚本不入库
- 仓库不再包含内部交接文档；截图、个人工作日志、图标预览等本地文件继续由 `.gitignore` 排除

## [v2.0.0] 导航流程对齐高德（第 4 批：多方案对比气泡 / 路线镜头） · 2026-11-22 晚

本批补齐选路页最后一块高德特征，并做了 5 屏验收（首页 → 搜索 → 选路 → 导航中 → 到达页，真机截图）。

- **多方案对比气泡**：算路后在地图分叉处显示白底圆角小卡「慢11分钟 / 少10.4公里 · 多29个红绿灯」
  （相对当前选中方案计算用时/距离/红绿灯差异），点气泡即切换方案；切换或重算时自动清理旧气泡。
  实现：`AMapNaviPath.getCoordList()` 找分叉点（相距 > 250m 的第一处），`MarkerOptions.icon(bitmap)` 画文字气泡，
  `AMap.OnMarkerClickListener` 处理点击。
- **非选中方案自动置灰**：开启 `AMapNaviViewOptions.setAfterRouteAutoGray(true)`，与高德一致（选中方案彩色、备选灰色）。
- **镜头适配整条路线**：算路成功后 `getBoundsForPath()` + `newLatLngBoundsRect` 把整条路线缩进视野
  （底部为方案卡留 300dp），并延迟 900ms 再适配一次，避免被 SDK 自身的镜头逻辑覆盖。
- **方案卡按用时排序**：用时最短的「推荐·最快」排第一，其余按用时升序（原来 HashMap 顺序随机）。
- 到达页弹出时清理对比气泡。

## [v2.0.0] 导航流程对齐高德（第 3 批：历史记录 / 首页最近去过） · 2026-11-22 晚

- **新增本地历史 `History`**：SharedPreferences 存「最近目的地」（名称/坐标/时间，最多 10 条）与「最近搜索词」，
  同名同坐标自动去重并提前；纯 Java 逻辑，坐标仍走 `AmapLink.isInChina` 校验。
- **路线页空态对齐高德**：搜索框下方新增「历史路线」（我的位置 → X）与「历史搜索」两块，点一条直接导入/搜索；
  选定目的地或展示搜索结果时自动隐藏，避免和主体抢版面。
- **首页「最近去过」**：底部卡片在未选点时列出最近 3 个目的地，点一条直接带坐标进导航（高德首页习惯）。
- 偏好入口位置修正：从出行方式 Tab 上方挪到 Tab 下方，顺序与高德一致（Tab → 偏好入口 → 历史）。
- 记录时机：搜索选中/地图选点/链接导入/历史点选都会写入历史；POI 搜索命中后记录关键词（http 链接不记）。

## [v2.0.0] 导航流程对齐高德（第 2 批：路线偏好 / 选路卡 / 到达页） · 2026-11-22 晚

- **路线偏好（高德式底部弹层）**：新增「路线偏好」卡（标题 + 关闭 + 两行胶囊 + 确定），选项为
  高德智能推荐 / 高速优先 / 不走高速 / 躲避拥堵 / 少收费 / 时间优先，选中项蓝色高亮；入口在选路页与方案卡标题行。
  策略改为直接使用 `PathPlanningStrategy` 常量（10/19/13/12/14/11），替代原来靠 `strategyConvert` 猜参数；
  实测切「躲避拥堵」后方案从 43 分钟/32.1 公里变为 44 分钟/21.8 公里，算路偏好真实生效。
- **修复「开始导航」按钮消失（严重）**：`prepareRoute()` 会把按钮设为 GONE，而出方案后从未重新显示，
  导致自动算路后没有任何按钮能开始导航；现在出方案时恢复显示。
- **选路卡片区收紧**：面板高度由 45%（最多 320dp）降到 40%（最多 250dp），去掉方案卡与「开始导航」之间的大片留白；
  方案卡标题行右侧新增「路线偏好」入口。
- **到达页改高德式底部白卡**：原来是居中弹窗，现改为底部圆角白卡（地图仍可见）：标题「已到达目的地附近」+ ✕、
  用时/目的地/手环同步状态、蓝色「重新规划」+「返回首页」；同时修复 FrameLayout 按 elevation 排序导致
  到达页被选路卡盖住的问题（endPage elevation 提到 24）。
- 调试钩子扩展（仅开发用）：`debug_pref`、`debug_show_pref`、`debug_arrive`，配合 `debug_auto_nav`
  可在 MIUI 禁止 adb 注入触控的情况下截取任意状态做并排对比。

## [v2.0.0] 导航流程对齐高德（第 1 批：首页→搜索→选路→导航中） · 2026-11-22 晚

以真机（红米 K70）与高德官方 App 并排截图对比为验收标准，逐屏对齐。版本号不变，APK 可直接覆盖安装。

- **搜索结果页（高德化）**：列表头「搜索结果 · N 个」；结果行改为「定位图标 + 蓝色加粗名称 + 类型·地址 + 右侧距离 + 去这里」，
  距离统一用「公里 / 米」；SDK 未返回距离时用当前定位本地计算（球面距离）；列表出现时隐藏「未选择目的地」占位。
- **选路页（高德化）**：起终点改为绿点「我的位置」/ 红点「输入终点」两行 + ⇅；出行方式由三个大按钮改为文字 Tab（选中蓝字加粗），
  链接导入/切换方式时同步选中态；驾车偏好胶囊提到页面上（文案「高德智能推荐」）；终点行回车即搜索；
  选定目的地后地图镜头自动跟随该点（原新实例会停在 SDK 默认视角）。
- **自动算路（高德行为）**：选定目的地（搜索选中 / 地图选点 / 首页直达 / 链接导入）后立即按当前出行方式算路并弹出方案卡片，
  不再需要再点一次「驾车」。
- **导航中（高德化）**：手环「已投屏 / 未连接」角标从右上角挪到转向面板下方右侧，不再压住「212米 / 道路名」，也不挡 SDK 速度表；
  「结束」按钮与角标并排；导航中隐藏「选择目的地」浮层；退出/返回仍保留二次确认。
- **调试钩子（仅开发用）**：`NavSdkActivity` 支持 `--ez debug_auto_nav true`，算路成功后自动开始导航，
  用于 MIUI 禁止 adb 注入触控时的真机截图验证（不影响正常使用）。
- 参考截图与差距清单见本地 `D:\dsh_workspaces\handover-verify\AMAP-PARITY.md`（不入库）。

## [v2.0.0] 修复：分享入口 / 坐标误判 / 短链解析 / 定位卡死 · 2026-11-22

本轮针对交接审计发现的问题做修复，版本号不变（APK 2.0.0 / versionCode 9，rpk 2.0.0 / versionCode 4，手环端协议未变）。
修复后 APK sha256 = `7de414e8f74207bc456e44fbb647d31306ddd2a1ff08bf103335a146798924c5`，证书 SHA-256 仍为 `df9f5ee5…0cf6`（与 7 个 rpk 一致），可直接覆盖安装。

- **系统分享入口（此前实际不生效）**：`NavSdkActivity` 现在读取 `Intent.EXTRA_TEXT`/`CharSequence`（`ACTION_SEND` text/plain），
  在高德 App 里「分享 → 手环导航」可直接导入目的地或整条路线；页面内输入 `nav_input` 仍兼容。
- **导出入口收口**：`dest_lat/dest_lng` 只接受「有限值 + 中国范围」（`AmapLink.isInChina`）；
  短链抓取只允许 `amap.com` 域名，不再接受任意 http(s) 链接（避免被外部 App 指使抓内网/任意 URL）。
- **坐标误判（高）**：裸文本坐标收紧为「两侧都必须带小数点、且落在中国范围」（lng 73~136 / lat 3~54），
  删除「海外或非常规也按 lat,lng 猜」的兜底。实测「35,000」「2026,11」「36,120」不再被当成坐标；
  短链 URL 本身也不做离线解析（URL 里的数字不是坐标）。
- **短链只抓一次**：新增 `AmapLink.resolveAny()`，路线/单点共用同一次 HTTP 抓取，避免双线程重复请求与 UI 状态竞争；
  解析失败会把原因显示在状态栏，不再静默停在「正在识别…」。`parseRoute` 在没有有效终点时返回 `null`。
- **定位等待可失败/可重试**：权限授予后自动补一次定位并继续算路；定位失败复位等待状态并提示；
  等待定位挂 15s 超时兜底，避免永久卡在「等待定位…」。
- **多途经点**：重复的 `&via=` 全部收集（此前只取第一个）；途经点名称只按 `|` 切分，避免名称含逗号时错位。
- **测试/CI**：`tools/test-amaplink.sh` 断言 14 → 30 条，且不再依赖 android.jar（AmapLink 是纯 Java），CI 增加该自测步骤。

## [v2.0.0] UI 改版 + 机型扩展 + 工程精简 · 2026-11-21（续）

作者在 v2.0.0 发布当日继续补全 Pro 机型：新增 8 Pro / 10 Pro 两个手环工程，
手环覆盖由 5 款扩展到 **7 款（8 / 8Pro / 9 / 9Pro / 10 / 10Pro / 11）**。版本号不变。

- **补齐 Pro 全系列**：新增 `band-app-8-pro`（手环 8 Pro）、`band-app-10-pro`（手环 10 Pro），
  与 9 Pro 同为 336×480 方屏，均用 `designWidth 336`；App 名分别为「手环导航8Pro」「手环导航10Pro」
  便于区分。小米手环 11 Pro 截至 2026-11 尚未发布（仅认证/爆料），暂不适配。
- **首页地图选点**：手机端首页改为长按地图直接落点 + 逆地理解析地址 + 「去这里」直达导航，
  搜索/选点不再需要跳到导航页；首页 UI 精简为搜索栏 + 定位钮 + 一张上下文卡片。
- **构建/CI 全覆盖**：build-all / gen-signing / check-env / fetch-deps / CI manifest 校验 /
  协议校验全部覆盖 7 个机型，CI 增加 designWidth 断言（胶囊 192、Pro 方屏 336）。

## [v2.0.0] UI 改版 + 机型扩展 + 工程精简 · 2026-11-21

作者：**往返**

本版是一次大版本更新，界面全面改为高德风格，机型覆盖扩展到 5 款，并做了死代码清理。

- **版本号升至 2.0.0**：手机端 APK `versionName 2.0.0 / versionCode 9`、手环端 5 个 rpk `versionName 2.0.0 / versionCode 4`。与 v1.1.0 同一把证书，可覆盖升级；从 v1.0.0 升级仍需先卸载。
- **手机端 UI 高德风格化**：首页改为全屏地图 + 悬浮搜索栏 + 底部「去哪儿」卡片；进入首页自动定位到当前位置（补运行时定位权限申请与拒绝提示）；新增高德链接解析器 `AmapLink`（标准链接/短链/裸坐标/文本坐标，经纬度顺序自动判别）；导航页去掉调试信息条，改为极简「手环已投屏」角标；目的地面板与地图选点页改浅色卡片风格。
- **机型扩展到 5 款**：新增 `band-app-8`（手环 8）、`band-app-10`、`band-app-11` 三个手环工程，连同原有 9 / 9Pro 共 5 个 rpk。构建/签名/CI/协议校验脚本全部覆盖 5 端；手环 10/11 保持 `designWidth 192` 由框架按屏宽等比缩放。互联通道 `system.interconnect` 在 8/10/11 上仍需真机验证。
- **启动图标更换**：手环桌面图标由照搬高德帆船改为蓝色圆角底板 + 白色导航箭头（128×128 RGBA），转向箭头保持纯白。
- **Bug 修复**：版本号从 Manifest 读取（不再写死）；坐标解析兼容带空格/中英文逗号；补定位权限回调；手环 `lastSeq` 在 stop 时重置 + 手机端序号改时间戳基准，修复重启后不震动；清理 `stopNavigation` 的 `navActive` 脏写法。
- **工程精简（ponytail）**：Repo 删孤儿日志缓冲与 5 个死字段（73→38 行，log 退化为 logcat 输出）；删 `NavFrame.key()`、Bridge 签名错误死状态、`arrived` 字段、空的 `updateFrameUi()`。Bridge 838 行反射（无文档 SDK 必需复杂度）保留。CI 新增手机端 turn 与 5 个手环端 ICONS 的协议一致性校验。
- 发布物：`cn.dsh.gaodenav.apk`、5 个机型 rpk（`手环导航-手环9/9Pro/8/10/11.rpk`）、`BUILD-REPORT.txt`。

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
