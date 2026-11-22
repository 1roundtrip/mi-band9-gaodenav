# 手环端快应用（band-app-8）— 小米手环 8 / 8NFC

小米手环 8 的 Vela 快应用，接收手机端通过小米互联发来的导航 JSON 帧并渲染。

## 与手环 9 版的关系

手环 8 与手环 9 / 9NFC 屏幕规格**完全相同（192×490，designWidth 192）**，因此本工程与 `band-app/` 的代码、样式、协议完全一致，可以直接互换使用。单独建目录只是为了独立命名产物、便于分别安装和排查。

> ⚠️ 手环 8 能否收到互联推送需真机验证：能装 Vela 快应用 ≠ 互联通道（`system.interconnect`）一定可用。若推送不通，说明该机型互联 SDK 不支持。

## 工程结构

```
band-app-8/
├── package.json            # aiot-toolkit 构建配置
├── src/
│   ├── manifest.json       # 包名 cn.dsh.gaodenav，designWidth 192
│   ├── app.ux              # 应用入口
│   ├── config-watch.json
│   ├── pages/index/index.ux  # 解析帧 + 渲染 + 震动
│   └── common/             # 8 个转向图标 + logo
├── sign/{debug,release}/   # 证书（由 tools/gen-signing.sh 分发，不进仓库）
└── dist/                   # 构建产物 .rpk（不进仓库）
```

## 构建与签名

```bash
export KS_PASS='你的密码'
bash ../tools/gen-signing.sh          # 生成并分发到全部手环工程
npm install
npx aiot release                       # 正式包 → dist/*.rpk
python3 ../tools/verify-rpk-cert.py dist/<产物>.rpk ../keys/certificate.pem
```

根目录一键构建全部机型：`bash tools/build-all.sh --skip-apk`。

安装：手机「表盘自定义工具」（米坛）→ 蓝牙一键安装选中 `.rpk`。若装过其他证书的同名快应用，需先卸载旧的。

签名原理与常见问题见 **[../SIGNING.md](../SIGNING.md)**。
