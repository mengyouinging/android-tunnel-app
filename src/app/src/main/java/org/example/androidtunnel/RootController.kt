package org.example.androidtunnel

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask

/** All shell arguments are fixed or validated; never execute strings received from the relay. */
object RootController {
    data class Result(val exitCode: Int, val output: String)
    private const val BIN = "/data/local/tmp/android-tunnel"
    private const val RUNTIME = "/data/user/0/org.example.androidtunnel/files/runtime"
    private const val ASSETS = "/data/user/0/org.example.androidtunnel/files/components"
    private val active = listOf("amneziawg-go", "sing-box", "cloudflared")
    private val components = ComponentAssets.names.toSet()
    private val ifacePattern = Regex("[A-Za-z0-9_.-]{1,15}")
    
    private fun exec(command: String, stdin: String? = null, timeoutSeconds: Long = 12): Result {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val reader = FutureTask { process.inputStream.bufferedReader().use { it.readText().take(65536) } }
        Thread(reader, "root-output").apply { isDaemon = true }.start()
        try {
            process.outputStream.use { if (stdin != null) it.write(stdin.toByteArray(Charsets.UTF_8)) }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("root 命令超时")
            }
            return Result(process.exitValue(), reader.get(2, TimeUnit.SECONDS))
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    // Compatibility with the allowlist regression test. Arbitrary commands remain inaccessible.
    fun run(script: String): Result {
        require(script == "id -u") { "拒绝执行未授权 root 命令" }
        return exec(script)
    }
    fun hasRoot() = runCatching { run("id -u").let { StartupPolicy.rootGranted(it.exitCode, it.output) } }.getOrDefault(false)

    // Read-only diagnostics. Each method builds commands only from the fixed component allowlist.
    fun diagnosticRootUid(): Result = exec("id -u")

    fun diagnosticComponent(name: String): Result {
        require(name in components)
        val asset = "$ASSETS/$name"
        val installed = "$BIN/$name"
        val command = """
            printf 'asset_exists='; test -f $asset && echo yes || echo no
            printf 'installed_exists='; test -f $installed && echo yes || echo no
            printf 'installed_executable='; test -x $installed && echo yes || echo no
            printf 'asset_size='; if test -f $asset; then stat -c %s $asset 2>/dev/null || echo unavailable; else echo unavailable; fi
            printf 'installed_size='; if test -f $installed; then stat -c %s $installed 2>/dev/null || echo unavailable; else echo unavailable; fi
            printf 'asset_sha256='; if test -f $asset; then sha256sum $asset 2>/dev/null | cut -d ' ' -f 1; else echo unavailable; fi
            printf 'installed_sha256='; if test -f $installed; then sha256sum $installed 2>/dev/null | cut -d ' ' -f 1; else echo unavailable; fi
        """.trimIndent().replace("\n", "; ")
        return exec(command)
    }

    fun diagnosticProcess(name: String): Result {
        require(name in active)
        val pidFile = "$RUNTIME/$name.pid"
        val binary = "$BIN/$name"
        val command = """
            printf 'pid_file_exists='; test -f $pidFile && echo yes || echo no
            if test -s $pidFile; then
              p=${'$'}(cat $pidFile); printf 'pid='; echo "${'$'}p"
              case "${'$'}p" in ''|*[!0-9]*) echo 'pid_valid=no';;
                *) echo 'pid_valid=yes'; printf 'kill_0='; kill -0 "${'$'}p" 2>/dev/null && echo alive || echo dead
                   actual_exe=${'$'}(readlink "/proc/${'$'}p/exe" 2>/dev/null)
                   printf 'proc_exe='; echo "${'$'}{actual_exe:-unavailable}"
                   printf 'expected_exe='; echo $binary
                   if test "${'$'}actual_exe" = $binary; then echo 'exe_matches_expected=yes'; else echo 'exe_matches_expected=no'; fi
                   printf 'cmdline='; tr '\000' ' ' < "/proc/${'$'}p/cmdline" 2>/dev/null || echo unavailable; echo
              esac
            else
              echo 'pid=unavailable'; echo 'pid_valid=no'; echo 'kill_0=unavailable'
              echo 'proc_exe=unavailable'; echo 'expected_exe=$binary'; echo 'cmdline=unavailable'
            fi
        """.trimIndent().replace("\n", "; ")
        return exec(command)
    }

    fun diagnosticSmoke(name: String): Result {
        require(name in components)
        return exec("timeout 5s $BIN/$name --help", timeoutSeconds = 8)
    }

    fun diagnosticTun(): Result = exec("""
        printf '%s\n' 'ls -l /dev/net/tun:'
        ls -l /dev/net/tun 2>&1
        printf 'test_c='; test -c /dev/net/tun && echo yes || echo no
        printf '%s\n' 'ip link show awg0:'
        ip link show awg0 2>&1
    """.trimIndent().replace("\n", "; "))

    fun diagnosticRoutes(): Result = exec("""
        printf '%s\n' 'IPv6 default route:'
        ip -6 route show default 2>&1
        printf '%s\n' 'IPv4 default route:'
        ip -4 route show default 2>&1
    """.trimIndent().replace("\n", "; "))

    private fun ok(command: String) = exec(command).exitCode == 0
    private fun check(command: String, message: String) { require(ok(command)) { message } }

    // TUN may be absent after a reboot. Only create a missing node; never replace an existing path.
    fun ensureTunDevice(): Boolean = runCatching {
        ok("if test -e /dev/net/tun; then test -c /dev/net/tun; else " +
            "mkdir -p /dev/net && mknod /dev/net/tun c 10 200 && chmod 600 /dev/net/tun && " +
            "test -e /dev/net/tun && test -c /dev/net/tun; fi")
    }.getOrDefault(false)

    fun installComponents(files: Map<String, File>): Boolean {
        if (!ok("mkdir -p $BIN && chmod 700 $BIN")) return false
        return components.all { name ->
            require(files[name]?.absolutePath == "$ASSETS/$name")
            ok("cp $ASSETS/$name $BIN/$name && chmod 700 $BIN/$name")
        }
    }
    fun generatePrivate(): String {
        val value = exec("$BIN/awg genkey").let { require(it.exitCode == 0); it.output.trim() }
        require(V2.key(value)) { "密钥生成失败" }
        return value
    }
    fun publicKey(privateKey: String): String {
        require(V2.key(privateKey))
        val value = exec("$BIN/awg pubkey", "$privateKey\n").let { require(it.exitCode == 0); it.output.trim() }
        require(V2.key(value)) { "公钥推导失败" }
        return value
    }

    fun startComponent(name: String): Boolean {
        require(name in active)
        val args = when (name) {
            "amneziawg-go" -> "-f awg0"
            "sing-box" -> "run -c $RUNTIME/sing-box.json"
            else -> "tunnel --no-autoupdate --metrics 127.0.0.1:${V2.METRICS_PORT} run --token-file $RUNTIME/cloudflared.conf"
        }
        // PID files contain no credentials. Foreground mode keeps the tracked PID alive.
        val pid = "$RUNTIME/$name.pid"
        return ok("nohup $BIN/$name $args >/dev/null 2>&1 </dev/null & echo \$! > $pid; sleep 1; kill -0 ${'$'}(cat $pid)")
    }
    fun alive(name: String): Boolean {
        require(name in active)
        val pid = "$RUNTIME/$name.pid"
        return ok("test -s $pid && p=${'$'}(cat $pid) && test \"${'$'}(readlink /proc/\$p/exe)\" = $BIN/$name && kill -0 \$p")
    }
    fun stopComponent(name: String) {
        require(name in components)
        val pid = "$RUNTIME/$name.pid"
        exec("if test -s $pid; then p=${'$'}(cat $pid); case \"\$p\" in ''|*[!0-9]*) exit 1;; esac; if test \"${'$'}(readlink /proc/\$p/exe)\" = $BIN/$name; then kill -TERM \$p; fi; rm -f $pid; fi")
    }
    fun configureAwg(): Boolean = ok("$BIN/awg setconf awg0 $RUNTIME/amneziawg-go.conf")
    fun lastHandshake(): Long {
        val result = exec("$BIN/awg show awg0 latest-handshakes")
        if (result.exitCode != 0) return 0
        return result.output.lineSequence().mapNotNull { it.trim().split(Regex("\\s+")).lastOrNull()?.toLongOrNull() }.maxOrNull() ?: 0
    }

    fun setupInterface(config: SharedConfig) {
        config.validate()
        check("ip link show awg0", "awg0 未创建")
        check("ip link set dev awg0 mtu 1280", "MTU 设置失败")
        config.interfaceAddresses.forEach {
            val address = V2.cidr(it)
            val family = if (address.contains(':')) "-6" else "-4"
            check("ip $family addr replace $address dev awg0", "隧道地址配置失败")
        }
        check("ip link set dev awg0 up", "awg0 无法启用")
        config.allowedIps.forEach {
            val address = V2.cidr(it)
            val family = if (address.contains(':')) "-6" else "-4"
            val existing = exec("ip $family route show exact $address")
            require(existing.exitCode == 0 && existing.output.lineSequence().filter(String::isNotBlank)
                .all { it.contains(Regex("(^|\\s)dev awg0(\\s|$)")) }) { "共享地址与已有路由冲突" }
            check("ip $family route replace $address dev awg0", "对端路由配置失败")
        }
    }

    private fun uplinkTable(family: String, iface: String): String? {
        require(family in listOf("-4", "-6") && iface.matches(ifacePattern))
        val result = exec("ip $family route show table all")
        if (result.exitCode != 0) return null
        return result.output.lineSequence().map { it.trim().split(Regex("\\s+")) }.firstNotNullOfOrNull { words ->
            if (words.firstOrNull() != "default" || words.getOrNull(words.indexOf("dev") + 1) != iface) null
            else {
                val idx = words.indexOf("table")
                (if (idx < 0) "main" else words.getOrNull(idx + 1))
                    ?.takeIf { it == "main" || it.matches(Regex("[A-Za-z0-9_]{1,32}")) }
            }
        }
    }

    /** Each successful change is journalled before applying it so stop/restart can undo it.
     * No flush, global firewall policy change, or whole-table replacement. */
    fun setupNat(config: SharedConfig, iface: String, journal: File): List<String> {
        require(iface.matches(ifacePattern) && iface.first().isLetterOrDigit() && iface != "awg0" && iface != "lo")
        val bootId = exec("cat /proc/sys/kernel/random/boot_id").output.trim()
        require(bootId.matches(Regex("[a-fA-F0-9-]{36}"))) { "无法读取系统启动标识" }
        val bootFile = File(journal.parentFile, journal.name + ".boot")
        bootFile.writeText(bootId)
        val warnings = mutableListOf<String>()
        val applied = mutableListOf<String>()
        fun undoLater(inverse: String) {
            applied += inverse
            journal.writeText(applied.joinToString("\n", postfix = "\n"))
        }
        fun change(add: String, del: String, message: String) {
            undoLater(del)
            if (!ok(add)) {
                applied.removeAt(applied.lastIndex)
                journal.writeText(applied.joinToString("\n", postfix = "\n"))
                error(message)
            }
        }
        fun rule(tool: String, table: String, chain: String, spec: String) {
            val prefix = "$tool -t $table"
            // The dedicated tag distinguishes these rules from other apps/tethering.
            change("$prefix -I $chain 1 $spec", "$prefix -D $chain $spec", "出口防火墙/NAT 设置失败：$tool")
        }
        try {
            for (family in listOf("-4", "-6")) {
                val ipv6 = family == "-6"
                val tool = if (ipv6) "ip6tables" else "iptables"
                val ranges = config.allowedIps.filter { it.contains(':') == ipv6 }.map(V2::cidr)
                if (ranges.isEmpty()) {
                    warnings += if (ipv6) "中转站未分配 IPv6 对端地址" else "中转站未分配 IPv4 对端地址"
                    continue
                }
                val table = uplinkTable(family, iface)
                if (table == null) {
                    warnings += if (ipv6) "当前上网接口无 IPv6 默认路由" else "当前上网接口无 IPv4 默认路由"
                    continue
                }
                val sysctl = if (ipv6) "/proc/sys/net/ipv6/conf/all/forwarding" else "/proc/sys/net/ipv4/ip_forward"
                val prior = exec("cat $sysctl").output.trim()
                require(prior in listOf("0", "1")) { "无法读取系统转发状态" }
                if (ipv6 && prior == "0") {
                    val ra = "/proc/sys/net/ipv6/conf/$iface/accept_ra"
                    val oldRa = exec("cat $ra").output.trim()
                    if (oldRa == "1") change("echo 2 > $ra", "echo 1 > $ra", "无法保留 IPv6 路由公告")
                }
                if (prior == "0") change("echo 1 > $sysctl", "echo 0 > $sysctl", "无法开启系统转发")
                val tag = "-m comment --comment android-tunnel-v2"
                ranges.forEach { cidr ->
                    rule(tool, "filter", "FORWARD", "-i awg0 -o $iface -s $cidr $tag -j ACCEPT")
                    rule(tool, "filter", "FORWARD", "-i $iface -o awg0 -d $cidr -m conntrack --ctstate ESTABLISHED,RELATED $tag -j ACCEPT")
                    rule(tool, "nat", "POSTROUTING", "-s $cidr -o $iface $tag -j MASQUERADE")
                    // Android policy routing: return traffic to the tunnel through main, then route
                    // tunnel-origin traffic via the actual network table. Restrict to the peer CIDR.
                    change("ip $family rule add pref 9998 to $cidr lookup main",
                        "ip $family rule del pref 9998 to $cidr lookup main", "对端策略路由设置失败")
                    change("ip $family rule add pref 9999 iif awg0 from $cidr lookup $table",
                        "ip $family rule del pref 9999 iif awg0 from $cidr lookup $table", "出口策略路由设置失败")
                }
            }
            // The direct UDP listener is only reachable through the chosen upstream interface.
            rule("ip6tables", "filter", "INPUT", "-i $iface -p udp --dport ${V2.PORT} -m comment --comment android-tunnel-v2 -j ACCEPT")
            require(applied.any { it.contains("MASQUERADE") }) { "没有可用的出口地址族" }
            return warnings
        } catch (e: Exception) {
            cleanupNat(journal)
            throw e
        }
    }

    // Validate journal lines too; file storage is private, but never trust arbitrary shell text.
    private fun validUndo(line: String): Boolean {
        if (line in setOf("echo 0 > /proc/sys/net/ipv4/ip_forward", "echo 0 > /proc/sys/net/ipv6/conf/all/forwarding")) return true
        val nic = "[A-Za-z0-9][A-Za-z0-9_.-]{0,14}"
        val cidr = "[0-9A-Fa-f:./]+"
        val tool = "(ip6tables|iptables)"
        val tag = "-m comment --comment android-tunnel-v2"
        return listOf(
            "echo 1 > /proc/sys/net/ipv6/conf/$nic/accept_ra",
            "$tool -t filter -D FORWARD -i awg0 -o $nic -s $cidr $tag -j ACCEPT",
            "$tool -t filter -D FORWARD -i $nic -o awg0 -d $cidr -m conntrack --ctstate ESTABLISHED,RELATED $tag -j ACCEPT",
            "$tool -t nat -D POSTROUTING -s $cidr -o $nic $tag -j MASQUERADE",
            "ip6tables -t filter -D INPUT -i $nic -p udp --dport 51820 $tag -j ACCEPT",
            "ip -(4|6) rule del pref 9998 to $cidr lookup main",
            "ip -(4|6) rule del pref 9999 iif awg0 from $cidr lookup [A-Za-z0-9_]{1,32}"
        ).any { line.matches(Regex(it)) }
    }
    private fun undo(line: String): Boolean {
        if (!validUndo(line)) return false
        if (line.startsWith("echo 1 > /proc/sys/net/ipv6/conf/") &&
            ok("test ! -e ${line.substringAfter("> ")}")) return true
        val result = exec(line)
        if (result.exitCode == 0) return true
        // A removed network can already have removed its associated route/rule.
        val text = result.output.lowercase()
        return (line.startsWith("ip ") && "no such file or directory" in text) ||
            (line.startsWith("ip") && line.contains(" -t ") &&
                ("bad rule (does a matching rule exist" in text || "no chain/target/match by that name" in text))
    }
    fun cleanupNat(journal: File): Boolean {
        if (!journal.exists()) return true
        val bootFile = File(journal.parentFile, journal.name + ".boot")
        val currentBoot = exec("cat /proc/sys/kernel/random/boot_id").output.trim()
        if (bootFile.isFile && currentBoot.matches(Regex("[a-fA-F0-9-]{36}")) &&
            bootFile.readText().trim() != currentBoot) {
            journal.delete(); bootFile.delete(); return true
        }
        val failed = mutableListOf<String>()
        journal.readLines().asReversed().filter { it.isNotBlank() }.forEach { line ->
            if (!runCatching { undo(line) }.getOrDefault(false)) failed += line
        }
        if (failed.isEmpty()) { journal.delete(); bootFile.delete() }
        else journal.writeText(failed.asReversed().joinToString("\n", postfix = "\n"))
        return failed.isEmpty()
    }
    fun cleanupInterface() { runCatching { ok("ip link delete awg0") } }
    fun stopAll(journal: File): Boolean {
        components.toList().asReversed().forEach { runCatching { stopComponent(it) } }
        val clean = runCatching { cleanupNat(journal) }.getOrDefault(false)
        cleanupInterface()
        return clean
    }
}
