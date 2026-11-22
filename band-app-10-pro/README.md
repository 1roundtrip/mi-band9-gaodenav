# 手环端快应用（小米手环 10 Pro 版）

与 `../band-app-pro/`（9 Pro 版）布局完全相同——10 Pro 与 9 Pro 都是 336×480 方屏，
只是 App 名不同，方便在表盘工具里区分。

| 机型 | 屏幕 | designWidth | 使用目录 |
| --- | --- | --- | --- |
| 小米手环 10 Pro | 336×480 | 336 | `band-app-10-pro/` |

- 包名与证书必须与手机端一致（`cn.dsh.gaodenav` + 同一 keystore），否则小米互联会报签名不一致
- 构建与签名流程同 `../SIGNING.md`：
  `bash ../tools/gen-signing.sh`（PEM 会同时分发到本目录）→ `npm install` → `npx aiot release`
  → `python ../tools/verify-rpk-cert.py dist/<产物>.rpk ../keys/certificate.pem`
- 安装：表盘自定义工具 → 工具 → 蓝牙一键安装 → 选 `手环导航-手环10Pro.rpk`
