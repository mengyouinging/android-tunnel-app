# Android Tunnel App（一键隧道服务端）

开源 Kotlin 原生 Android 应用：已 root 手机上一键启停全部隧道服务
（WireGuard/AmneziaWG 服务端、sing-box VLESS Reality、DDNS、EasyTier 回退、
cloudflared Tunnel 仅应急），IPv6 直连优先。工单见源码包内的 `README.md`
与 `WORK_ORDER_ANDROID_TUNNEL.md`（后者在 Codex 机器上，见下）。

## 源码包（本次验收的唯一输入）

- `android-tunnel-source.zip` — 89,910 B，61 条目（37 个文件）
- SHA-256：`61e26283ff68ad2bee052fdbff552bd21c7a96f5b20672ebaffcfe1835b7c1ec`
- 已在本地实查对上（与 InstaBox 新 CP2 `/data/android-tunnel/deliverables/` 一致）。
- 源码包内含 6 个 arm64 二进制的 SHA-256 清单与 `scripts/fetch-upstream.sh`
  （可按 `upstream/SHA256SUMS.lock` 复现），但不含二进制本身；CI 里先跑
  `fetch-upstream.sh` 再构建。

## 已有实物（Codex 在 InstaBox 新 CP2 上构建，构建 5）

- `MoyuTunnel-release.apk` — 57,728,104 B，
  SHA-256 `02ef66fd9e0a1bea4a6ef6f3d52a992eb5b9b39517e20cc69f4a8f4165a2f71c`
- 本地已验：APK 内 6 个 arm64 二进制摘要与 lock 一致
  （amneziawg-go、awg 111,968 B、sing-box、easytier-core、cloudflared、wstunnel），
  含 v2 签名块（dev keystore），单测源码里共 19 个 `@Test`。
- 单测 19/19、构建成功是构建 5 的自述（`BUILD5_VERIFICATION.md`），
  本仓库的 CI 会重跑一遍才算数。

## 注意：源码包里的旧文档已过期

- 包内 `README.md` 仍写“18 项单测、没有执行 Android Gradle 完整构建”——
  那是 fix3 时的文字，已被构建 5 取代（19/19、`gradle testReleaseUnitTest
  assembleRelease` BUILD SUCCESSFUL），以 `BUILD5_VERIFICATION.md` 为准。
- 包内主界面有一处繁体字残留：`MainActivity` 默认状态文案“服務未啟動”。
- 域名、Cloudflare Token、中继 VPS、peer 密钥、Hysteria2 证书、手机型号与
  Android 版本仍是占位，须实机联调；无真机/模拟器安装验收、无 UI 截图——
  正是本仓库 CI 要补的。

## CI 验收（`.github/workflows/android.yml`）

1. `build`：解包 → `fetch-upstream.sh` 复现 6 个 arm64 二进制并校验 →
   生成 CI 专用 dev keystore（仅供验收，**不是**发布密钥）→
   `./gradlew testReleaseUnitTest assembleRelease` → 上传 APK 与测试报告。
2. `emulator`：在 Android 9（API 28，x86_64）模拟器上安装 CI 构建的 APK，
   依次启动主界面 / 运行状态 / 设置页，各截一张图，附 logcat，一并上传。
   - 模拟器是 x86_64：APK 里的 arm64 二进制只是 assets 文件，不影响安装与
     界面启动；隧道真实启停、root、`/dev/net/tun` 仍须真机验证。
   - 主界面默认文案等界面问题以截图为准逐张核对。

## 仍未验证（CI 也覆盖不了）

Magisk root、`/dev/net/tun`、ip/ip6tables、SELinux、Cloudflare API 写入、
IPv6 直连、EasyTier 中继、Tunnel、耗电、恢复测试；优选 IP 未绑定 cloudflared
实际边缘节点测速。
