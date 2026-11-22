# 手环端快应用（band-app-11）— 小米手环 11 / 11NFC

小米手环 11 的 Vela 快应用，接收手机端通过小米互联发来的导航 JSON 帧并渲染。

## 屏幕适配说明

手环 11 屏幕为 **212×520**（与手环 10 相同，手环 9 为 192×490）。两者 PPI 几乎相同，本工程沿用 `designWidth: 192` 作为设计基准，Vela 框架会按实际屏宽（212/192≈1.10）自动等比放大所有元素，占屏比例与手环 9 一致，样式无需改动。布局用 `flex` 自适应高度，520 的高度自动填充。

> ⚠️ 能否收到互联推送需真机验证：能装 Vela 快应用 ≠ 互联通道（`system.interconnect`）一定可用。手环 11 为最新平台（澎湃 OS 4），理论支持但未经实机确认。

## 工程结构

```
band-app-11/
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
