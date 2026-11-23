# 上游组件清单（已核验，2026-11-21，操作方在 cpu-3 沙箱外核实）

| 组件 | 固定版本 | 官方来源 | 二进制 SHA-256（assets/arm64/ 内文件） |
|---|---|---|---|
| amneziawg-go | 源码构建 tag `v3.1.20261116` → commit `08fe3d33bbb116be09a6183d02609311b8b0c9e8`，`GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -trimpath`（必须保留 Git 标签） | https://github.com/amnezia-vpn/amneziawg-go（该仓库无 release，故源码构建；Go 1.27.1） | fab6b1d82e3c1145df103578c5ba7b667c07f8ebba4fbaba0b5e5af89950fd5e |
| sing-box | v1.14.4，`sing-box-1.14.4-android-arm64.tar.gz`（归档 SHA-256 fb3a6c5197f4044f82c8fd75e01ce14c28b6abdd4c3df84bb987d41a962a320f） | https://github.com/SagerNet/sing-box/releases/download/v1.14.4/ | 2ce1c1c2a265e7be21ffa7aaf2b58619986629e9caecfc2d3f80ef4d25b7f965 |
| easytier-core | v2.6.4，`easytier-linux-aarch64-v2.6.4.zip`（归档 SHA-256 f533ec25a7ea714e09f645615012200278058525795cc3bb690ff011aec1a70f；静态链接，可在 Android 运行） | https://github.com/EasyTier/EasyTier/releases/download/v2.6.4/ | 7bd504e87cad52edacb9ea84ece7397d0afb911101de623a3dfc4a4dbd9e6bb6 |
| cloudflared | 2026.11.1，`cloudflared-linux-arm64`（直接二进制，Go 静态） | https://github.com/cloudflare/cloudflared/releases/download/2026.11.1/ | b155cec3e48e489cfb8e5c602769fb8c771083018d8881d68b4d18a791567c5a |
| wstunnel | v11.1.0，`wstunnel_11.1.0_android_arm64.tar.gz`（归档 SHA-256 6f00cc63740f1602168e5d6a5b0bc5dab6bbe77ef613e2b11fe68f881641ce7f，与官方 checksums.txt 一致） | https://github.com/erebe/wstunnel/releases/download/v11.1.0/ | 4e3bcaf352f6a1d00b897fe7de3716b3ae15f8d35df426948a05a03659cb4abf |
| awg | 源码 commit `ee0f0a9aa34ff0a0da4b3433b9512781cfe02843`，NDK r27c `27.2.12479018`，`aarch64-linux-android33-clang`，安装时 `wg` 重命名为 `awg` | https://github.com/amnezia-vpn/amneziawg-tools | 83d457ce84a0120f1307f5cd8c725cbaa23b82569ca2037487fcf3a9095ac2e6 |
| DDNS | App 内 Kotlin `CloudflareClient` / `DdnsUpdater` | 本 App MIT；未使用 ddns-go 二进制 | 不适用 |

许可证：各组件许可证见各自上游仓库 LICENSE（amneziawg-go/sing-box GPL 系列、cloudflared Apache-2.0、EasyTier/wstunnel 按上游仓库核验）。App 首次启动时校验 assets 内各二进制的 SHA-256，缺失或校验失败时明确报告组件缺失。

## 构建 6 修复（2026-11-23）

`SHA256SUMS.lock` 和 App 使用的 `assets/arm64/SHA256SUMS` 均原样保留。
amneziawg-go 必须克隆 `v3.1.20261116` 标签，并核对标签与 HEAD 都指向固定 commit。
仅取 commit 会改变 Go 写入的主模块版本信息，无法复现 lock 内的二进制摘要。
构建工具固定为 Go **1.27.1** 与 Android NDK **27.2.12479018**（r27c），禁止 Go 自动切换工具链。

执行 `bash scripts/fetch-upstream.sh` 下载/构建全部六个组件，再用
`bash scripts/verify-upstream.sh` 单独复验；后者同时核对 App 内摘要清单与 lock 一致。
实际结果和原始日志随本次源码交付，见源码包根目录 `REPAIR_REPORT.md`、`evidence/`。
