# 手环端快应用（小米手环 9 Pro 版）

与 `../band-app/`（手环 9 / 9NFC 版）功能完全相同，区别只在屏幕适配：

| 机型 | 屏幕 | designWidth | 使用目录 |
| --- | --- | --- | --- |
| 小米手环 9 / 9NFC | 192×490 | 192 | `band-app/` |
| 小米手环 9 Pro | 336×480 | 336 | `band-app-pro/` |

- 布局尺寸已按 9 Pro 的 336 宽重新换算（图标/字号/间距）
- 包名与证书必须与手机端一致（`cn.dsh.gaodenav` + 同一 keystore），否则小米互联会报签名不一致
- 构建与签名流程同 `../band-app/README.md` 与 `../SIGNING.md`：
  `bash ../tools/gen-signing.sh`（PEM 会同时分发到本目录）→ `npm install` → `npx aiot release`
  → `python3 ../tools/verify-rpk-cert.py dist/<产物>.rpk ../keys/certificate.pem`
- 安装：表盘自定义工具 → 工具 → 蓝牙一键安装 → 选对应机型构建出的 `.rpk`

> 若想简化维护，也可以只维护一份 ux，用 `designWidth=192` 让运行时按宽度等比缩放；本仓库提供两套独立布局是为了在 9 Pro 上显示更大更清晰。
