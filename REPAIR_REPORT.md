# 安卓隧道 App 构建 6 修复报告

交付日期：2026-11-23（America/Los_Angeles）。

## 结果

已基于附件构建 5 源码实际修改 fetch 脚本与 CI，保留原 lock，不重新计算或替换任何预期摘要。
在 Linux x86_64 环境实际从官方来源重新获取/编译六个 arm64 组件，**6/6 SHA-256 校验通过**。
amneziawg-go 的构建元数据确认主模块版本为 `v3.1.20261116`，二进制摘要为原 lock 中的
`fab6b1d82e3c1145df103578c5ba7b667c07f8ebba4fbaba0b5e5af89950fd5e`。

本次交付是用户指定的 fetch/CI 修复、完整源码、文件清单和报告。未向 GitHub 仓库推送，未触发或声称通过新的 GitHub Actions 运行；以下通过结果来自本次本地实际执行。没有在本轮生成 APK、UI 截图，也未执行 Android 模拟器或 root 真机验收。

## 基线与完整性

- 基线：附件 `android-tunnel-source.zip`，SHA-256：`61e26283ff68ad2bee052fdbff552bd21c7a96f5b20672ebaffcfe1835b7c1ec`。
- 原 ZIP 为 **61 个条目：37 个普通文件、24 个目录条目**。全部 37 个原文件均保留；4 个原文件修改、33 个原文件内容不变，无删除。
- `src/` 新增 2 个校验/回归测试脚本与 6 个已验证的运行文件，共 45 个普通文件；Kotlin 源码和原 JVM 单测均未修改。
- 包根目录另含实际生效的 GitHub workflow、报告、源码摘要清单和本次执行证据。
- `src/upstream/SHA256SUMS.lock` 原样保留，文件 SHA-256：`59edb0bca4bcd651af8ba306e07d700273f7ff3b579a924a1cb2612f633439cd`。
- `src/app/src/main/assets/arm64/SHA256SUMS` 原样保留，文件 SHA-256：`f13e155f62f869498da79ade5d80976fe1d85d04017a758023bb56d85c94cdda`。
- 完整对比见 `evidence/baseline-comparison.tsv`，记录原/新大小、摘要与状态。

## 修改了什么、为什么

| 文件 | 改动及理由 |
|---|---|
| `src/scripts/fetch-upstream.sh` | 将默认分支浅克隆改为 `--depth 1 --single-branch --branch v3.1.20261116`，保留本地 tag；同时核对 HEAD、tag 解引用 commit、`git describe --tags --exact-match`。任一不符立即失败，绝不修改 lock。 |
| 同上 | 固定 Go 1.27.1 与 NDK 27.2.12479018；设置 `GOTOOLCHAIN=local`、`GOENV=off`、`GOWORK=off`、清空 `GOFLAGS`，避免环境改变构建。保留原 `GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -trimpath`，打印构建元数据。 |
| 同上 | `amneziawg-tools` 改为显式 fetch 原固定 commit `ee0f0a9aa34ff0a0da4b3433b9512781cfe02843`，避免默认分支向前移动导致失败；NDK 编译参数不变。最终调用独立的六组件校验脚本。 |
| `src/.github/workflows/android.yml` | 附件中的工作流没有 fetch 步骤。本轮新增 `fetch-upstream` 前置任务，固定 Go/NDK、执行回归测试和实际下载/编译、保存日志/逐项摘要结果。Android 任务依赖它成功并下载已校验运行文件，在打包前再次校验。 |
| 同上 | 固定 runner 为 ubuntu-24.04；最小 `contents: read` 权限。为模拟器另构建并安装自动签名 debug APK，避免原来安装 unsigned release APK 失败；release 构建与环境变量签名配置保留。SDK license 管道只忽略 `yes` 的 SIGPIPE，保留 sdkmanager 的失败状态。 |
| `.github/workflows/android.yml`（新增） | 与上述修订版完全一致，位于 GitHub 实际识别的仓库根目录；`src/.github/...` 保留为附件原路径的同步副本。 |
| `src/scripts/verify-upstream.sh`（新增） | 严格要求六个组件及合法 SHA-256，逐项比较实际字节摘要；缺失、损坏、符号链接、重复/未知组件、缺少 lock 条目均失败；同时核对 App 的运行期 SHA256SUMS 与 lock 内容一致。输出 TSV，可在下载后、跨任务传递后或解压后独立复验。 |
| `src/scripts/test-verify-upstream.py`（新增） | 8 项离线回归测试验证校验失败会阻断。使用临时合成样本，不修改真实 lock 或六个实际运行文件。 |
| `src/README.md` | 补充构建 6 的工具链、标签要求、复验方法、仓库目录和 CI 行为；修正“五个组件/缺少 awg”等过时文字，区分历史验证与本轮结果。 |
| `src/upstream/UPSTREAM.md` | 写明必须保留 tag，补全 awg 固定来源、工具链和摘要；保留 lock。 |
| `src/app/src/main/assets/arm64/` 下六个运行文件（新增） | 附件只有摘要、没有运行文件；本次实际获取/构建并校验后随包提供，可直接进入 App 构建，不依赖未验证的旧资产。 |

其他新增文件均为本报告、摘要清单与执行证据。

## 采用已确认的根因与修复

按用户已确认的根因执行：即使 commit 相同，缺少版本 tag 时，Go 写入的主模块版本会不同，导致最终二进制不匹配 lock。因此取带标签的 Git 源码，同时锁定 tag → commit，不能只下载 commit 归档或仅按 commit checkout 而不获取 tag。

本次实际源码定位：

```text
tag:      v3.1.20261116
commit:   08fe3d33bbb116be09a6183d02609311b8b0c9e8
Go:       go1.27.1 linux/amd64
target:   GOOS=android GOARCH=arm64 CGO_ENABLED=0
command:  go build -trimpath -o <assets>/amneziawg-go .
NDK:      27.2.12479018 (r27c), aarch64-linux-android33-clang
```

实际 `go version -m` 的关键输出：

```text
mod github.com/amnezia-vpn/amneziawg-go/v3 v3.1.20261116
build vcs.revision=08fe3d33bbb116be09a6183d02609311b8b0c9e8
build vcs.modified=false
```

完整输出见 `evidence/amneziawg-go-buildinfo.txt`。

## 六组件实际校验结果

以下为**实际文件字节**的 SHA-256，全部与原 lock 的期望值逐项相等。TSV 同时保存 expected 与 actual，见 `evidence/upstream-verification.tsv`。

| 组件 | 文件大小（字节） | 实际 SHA-256（等于原 lock） | 结果 |
|---|---:|---|---|
| `sing-box` | 82765688 | `2ce1c1c2a265e7be21ffa7aaf2b58619986629e9caecfc2d3f80ef4d25b7f965` | **PASS** |
| `easytier-core` | 5910252 | `7bd504e87cad52edacb9ea84ece7397d0afb911101de623a3dfc4a4dbd9e6bb6` | **PASS** |
| `cloudflared` | 37818397 | `b155cec3e48e489cfb8e5c602769fb8c771083018d8881d68b4d18a791567c5a` | **PASS** |
| `wstunnel` | 11454320 | `4e3bcaf352f6a1d00b897fe7de3716b3ae15f8d35df426948a05a03659cb4abf` | **PASS** |
| `amneziawg-go` | 5218201 | `fab6b1d82e3c1145df103578c5ba7b667c07f8ebba4fbaba0b5e5af89950fd5e` | **PASS** |
| `awg` | 111968 | `83d457ce84a0120f1307f5cd8c725cbaa23b82569ca2037487fcf3a9095ac2e6` | **PASS** |

`sing-box`、EasyTier、wstunnel 的下载归档也先通过脚本中既有的归档摘要校验；cloudflared 是直接二进制，直接校验原摘要。amneziawg-go 与 awg 由固定源码实际交叉编译。`evidence/fetch-upstream.log` 包含完整执行日志和六项结果，脚本退出码 **0**。

`file` 识别六个文件均为 ARM aarch64 ELF；其中面向 Android 的程序使用 `/system/bin/linker64`，EasyTier/cloudflared 为静态 ELF。格式结果见 `evidence/binary-formats.txt`。本项不等同于在 Android 上成功执行它们。

## 其他验证

| 检查 | 结果 |
|---|---|
| 实际 fetch + 交叉编译 + 六组件摘要 | PASS，退出 0 |
| 独立复验脚本 | PASS，六项 expected = actual，退出 0 |
| App 内 SHA256SUMS 与 lock 语义一致 | PASS |
| 原 lock、原 App 摘要清单逐字节不变 | PASS |
| `bash -n` 检查 fetch/verify 脚本 | PASS |
| actionlint 1.7.12 检查根目录与原路径 workflow | PASS（未启用 ShellCheck），退出 0 |
| 合成样本回归测试 | 8/8 PASS：全部通过、损坏、缺文件、符号链接、运行期清单不符、重复组件、缺少 lock 组件、未知组件 |

回归测试原始输出见 `evidence/verifier-regression.log`，工作流语法结果见 `evidence/workflow-lint.log`。
Go 工具链归档已对照官方 JSON 校验 SHA-256，元数据见 `evidence/go-toolchain.json`；NDK r27c 归档大小和 SHA-1 与 Android 官方 repository 元数据一致。

## 文件清单与使用

- 包内 `SOURCE_FILE_MANIFEST.tsv`：覆盖 `src/` 和根目录 workflow 的每个普通文件，字段为相对路径、字节数、SHA-256。
- 包外 `android-tunnel-build6-FILE_MANIFEST.tsv`：覆盖源码 ZIP 内**所有普通文件**，包括报告、日志和包内源码清单；不将清单自身塞入被清单覆盖的 ZIP，避免摘要自引用。
- `DELIVERY_SHA256SUMS.txt`：记录源码 ZIP、独立报告和包外完整清单的摘要。

将 ZIP 内容按目录放入仓库根目录，保留 `.github/` 与 `src/` 的相对关系；GitHub 从根目录 `.github/workflows/android.yml` 启动工作流。独立复验：

```sh
cd src
bash scripts/verify-upstream.sh
python3 scripts/test-verify-upstream.py
```

如需重新获取全部上游组件，使用 Go 1.27.1，安装 NDK 后执行：

```sh
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
bash scripts/fetch-upstream.sh
```

在同一工具链下应得到上述六个固定摘要。任何不符都应失败并保留证据，不能通过重写 lock 来绕过。

## 验证边界

本次没有远程 GitHub CI 的新 run ID，因此不把“本地 fetch 通过 + 工作流语法通过”表述为“GitHub CI 已绿”。Android Gradle 构建、release 签名、模拟器安装截图、root/保活、IPv6 入站、DDNS、EasyTier 切换与 Tunnel 实机连通性仍需各自执行验收；附件中已有的相关限制继续有效。本轮没有声称工单全部功能或真机验收完成。
