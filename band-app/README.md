# 手环端快应用（band-app）

小米手环 9 / 9NFC 的 Vela 快应用，接收手机端通过小米互联发来的导航 JSON 帧并渲染。

## 工程结构

```
band-app/
├── package.json            # aiot-toolkit 构建配置（build / release 脚本）
├── src/
│   ├── manifest.json       # 包名、入口页、features、designWidth
│   ├── app.ux              # 应用入口
│   ├── config-watch.json
│   ├── pages/index/index.ux  # 唯一页面：解析帧 + 渲染 + 震动
│   └── common/             # 转向图标（8 个）与 logo
├── sign/
│   ├── debug/              # private.pem + certificate.pem（不进仓库）
│   └── release/            # private.pem + certificate.pem（不进仓库）
└── dist/                   # 构建产物 .rpk（不进仓库）
```

## manifest 要点

`src/manifest.json`：

```json
{
  "package": "cn.dsh.gaodenav",
  "name": "手环导航",
  "versionName": "1.1.0",
  "versionCode": 3,
  "minPlatformVersion": 1000,
  "deviceTypeList": ["watch"],
  "features": [
    { "name": "system.interconnect" },
    { "name": "system.vibrator" },
    { "name": "system.prompt" }
  ],
  "config": { "logLevel": "log", "designWidth": 192 },
  "display": { "backgroundColor": "#000000" },
  "router": { "entry": "pages/index", "pages": { "pages/index": { "component": "index" } } }
}
```

> 手环端版本号（`versionName/versionCode`）与手机端 APK 相互独立，可各自升级。

- **`package` 必须与手机端 APK 包名一致**（默认 `cn.dsh.gaodenav`），否则互联匹配不上
- **`designWidth: 192`**：手环 9 方形屏的基准宽度（UI 尺寸按这个换算）
- `features`：`system.interconnect`（小米互联收发帧）、`system.vibrator`（转向震动）、`system.prompt`（提示）
- `deviceTypeList: ["watch"]`

## 页面对接的帧协议

手机端每约 1 秒下发一帧（`@system.interconnect` 的 `onmessage` 里 `JSON.parse(res.data)`）：

```json
{"type":"nav","seq":12,"turn":"left","action":"左转",
 "distMeters":300,"remainSeconds":720,"remainMeters":3200,"vibrate":true}
{"type":"stop"}
```

- `turn`：`left | right | straight | slight-left | slight-right | uturn-left | uturn-right | arrive`
- 未知/省略的字段不显示（`distMeters` 缺失时不显示距离行）
- `seq` 递增；`vibrate !== false` 且 `seq` 变大时震动一次
- `{"type":"stop"}` → 回到待机界面「导航已结束」

## 构建与签名

```bash
# 0) 首次：准备签名（keystore -> PEM，并自动分发到 band-app 与 band-app-pro）
export KS_PASS='你的密码'
bash ../tools/gen-signing.sh

# 1) 构建
npm install          # 安装 aiot-toolkit 依赖（首次，约 1-2 分钟）
npx aiot build       # 调试包：使用 sign/debug 证书 → dist/*.rpk
npx aiot release     # 正式包：使用 sign/release 证书 → dist/*.rpk

# 2) 校验 rpk 里内嵌的确实是你自己的证书（aiot 找不到 sign/ 会静默用自带证书）
python3 ../tools/verify-rpk-cert.py dist/<产物>.rpk ../keys/certificate.pem
```

也可以在仓库根目录一键构建两个机型：`bash tools/build-all.sh --skip-apk`。

签名原理、指纹查看、aiot 静默回退自带证书的坑见 **[../SIGNING.md](../SIGNING.md)**。

要点：
- `sign/debug`、`sign/release` 放**同一套**证书（与 APK 相同），否则互连会报签名不一致
- `aiot` 找不到 sign 目录会**静默使用自带默认证书**，务必看日志确认路径
- 正式发布用 `aiot release` 产物

## 安装到手表

1. 手机上打开「表盘自定义工具」（米坛）
2. 登录米坛账号（安装第三方快应用需要捐赠者权限 / 试用捐赠者）
3. 在工具里填入 AuthKey（工具内有申请入口）
4. 选择「蓝牙一键安装」，选中构建出的 `.rpk`，保持手环靠近手机
5. 手环上出现「手环导航」应用后打开，手机端 App 点「连接手环」即可

> 如果之前装过**其他证书**签名的同名快应用，必须先在手环上卸载旧版本，再装新的（旧证书无法覆盖升级）。

## 适配其他机型

不同手环的屏幕尺寸不同，需要调整：

| 项 | 手环 9 / 9NFC | 其他机型（示例） |
| --- | --- | --- |
| `config.designWidth` | `192` | 9 Pro / 10 / 手表等按实际逻辑宽度改（如 194、480 等） |
| 字号/图标尺寸 | 见 `pages/index/index.ux` 的 style | 按比例调整 |
| `deviceTypeList` | `["watch"]` | 视平台支持保留 |

改完用**同一套证书**重新 `aiot release` 打包即可；APK 侧不需要改动（包名不变的话）。
如果改了包名，记得同步 APK 包名与高德 Key。
