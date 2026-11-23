# Android Tunnel

原生 Kotlin Android 工程（arm64）。fix3 增加了组件安装与 SHA-256 校验、服务配置生成、受限 root 命令、进程生命周期、TCP 对端探测和直连/EasyTier/应急管理通道选择。域名、Token、所有 peer 密钥、VPS 地址、证书路径、手机型号和 Android 版本仍需由使用方配置并待实机联调；源码不含真实凭据。

## 构建 6：标签保留修复

本次基于构建 5 附件修复上游获取与 CI。`upstream/SHA256SUMS.lock` 和 App 的
`app/src/main/assets/arm64/SHA256SUMS` 均保持原样。amneziawg-go 固定获取
`v3.1.20261116` 标签，并验证它指向 `08fe3d33bbb116be09a6183d02609311b8b0c9e8`；
不能用不带标签的 commit 快照替代。Go 固定 **1.27.1**，NDK 固定 **27.2.12479018**。

本次交付包含六个经 lock 校验的 arm64 运行文件，包括 `awg` 控制工具。复验与重新获取：

```sh
cd src
bash scripts/verify-upstream.sh
# 重新下载/源码构建时，先安装 Go 1.27.1 与 NDK r27c：
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
bash scripts/fetch-upstream.sh
```

仓库结构保持 `src/`；本包根目录已含可直接放入 GitHub 仓库根目录的
`.github/workflows/android.yml`，其余工程文件位于 `src/`。
`src/.github/workflows/android.yml` 是附件原路径的同步副本；GitHub 实际读取根目录版本。
CI 先完成 `fetch-upstream`，再向 Android 构建任务传递六个运行文件并重新校验；
校验失败就阻止 App 打包。CI 的模拟器安装对象改为自动签名的 debug APK，release
签名仍需上述环境变量配置。此处的模拟器步骤只覆盖安装、启动和 UI 截图，不代表 root/IPv6 实机验收。
本次结果与限制见交付包根目录 `REPAIR_REPORT.md` 和 `evidence/`。

## 历史本机验证（构建 5 附件原有记录）

本轮 Kotlin 2.0.21 主源码编译成功，18 项 JVM 单测通过。Gradle `testReleaseUnitTest` 因沙箱禁止 Gradle daemon 监听 socket 而未能启动；没有执行 Android Gradle 完整构建、APK 打包或真机测试。操作方须在沙箱外使用 JDK 21、Android SDK 35、Gradle 8.11.1 重新运行：

```sh
cd src
export ANDROID_HOME=/data/android-sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew testReleaseUnitTest assembleRelease
```

签名仍从 `ANDROID_TUNNEL_KEYSTORE`、`ANDROID_TUNNEL_STORE_PASSWORD`、`ANDROID_TUNNEL_KEY_ALIAS`、`ANDROID_TUNNEL_KEY_PASSWORD` 读取。不要将 keystore 或密码加入源码归档。

## 运行配置

设置页中的 Cloudflare Token、私钥、UUID、密码和网络密钥通过 Android Keystore 支持的 `SecretStore` 加密保存。运行配置在私有目录中生成，权限仅限应用所有者；进程停止时删除。六个二进制先解到应用私有目录并按 `assets/arm64/SHA256SUMS` 校验，再复制到 `/data/local/tmp/android-tunnel/` 供 root 启动。

启动前必须提供 AmneziaWG peer 私钥/公钥、Endpoint、AllowedIPs、本机隧道地址，VLESS Reality 参数、Hysteria2 证书/私钥路径、EasyTier 网络名/密钥与自建 relay、探测地址、Tunnel Token 和 wstunnel 管理地址。端口初次显示时随机选取 20000–60000 的高位端口。没有完整配置或任一组件校验失败时不会启动链路，并会显示具体缺失项。

root 命令通过 `RootController` 集中限制；执行包括接口地址/路由、MTU 1280、IPv6 INPUT/FORWARD 放行及组件启停。主链路探测失败后尝试 EasyTier，再失败才启 cloudflared 与只转发本机 SSH 管理端口的 wstunnel。状态延迟来自 TCP connect 实测；未收到对端响应时不显示延迟。

## 实机依赖与限制

- 六个 arm64 运行文件摘要来自 `app/src/main/assets/arm64/SHA256SUMS`；本次源码 zip 包含它们，也可按 `scripts/fetch-upstream.sh` 和 `upstream/SHA256SUMS.lock` 重新获取/构建。
- AmneziaWG 配置通过随包 `awg setconf` 控制工具应用。接口、SELinux、`/dev/net/tun`、iproute2 和 ip6tables 都需要实机验证，失败会回滚并报错。
- Hysteria2 需要用户提供有效证书文件。对端格式、EasyTier CLI 参数、cloudflared/wstunnel 远端设置与 Android 后台行为均未真机验证。
- 优选 IP 输入目前只保存并显示配置值；cloudflared 实际边缘节点选择/固定及实测边缘延迟尚未实现，界面不会把配置值伪报为实际节点。
- TCP 探测只测配置端点的 TCP 建连往返时间，不验证应用层身份或隧道内数据传输。状态仅表示端口收到 TCP 响应，不等同于完整业务链路验收。
- fix2 的 APK（若在 `deliverables/` 中）不是本轮源码构建产物，不包含 fix3 改动。当前源码不能据此 APK 安装验证。

历史报告路径 `../deliverables/ANDROID_REPORT.md` 和 `../deliverables/ANDROID_TEST_REPORT.md` 不在构建 5 附件中；本次修复不补写历史实测结论。
