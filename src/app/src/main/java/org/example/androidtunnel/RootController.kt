package org.example.androidtunnel

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** Root commands are limited to fixed commands and narrowly validated argument grammars. */
object RootController {
    data class Result(val exitCode: Int, val output: String)

    fun run(script: String): Result {
        require(script.lines().all { it.isBlank() || isAllowed(it) }) { "拒绝执行未授权 root 命令" }
        val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }.take(8192)
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("root 命令超时")
        }
        return Result(process.exitValue(), output)
    }

    fun hasRoot(): Boolean = try { run("id -u").let { it.exitCode == 0 && it.output.trim() == "0" } } catch (_: Exception) { false }

    fun startComponent(name: String, binary: String, config: String? = null): Boolean {
        require(name in COMPONENTS && binary == "/data/local/tmp/android-tunnel/$name")
        require(config == null || config.matches(Regex("/data/user/0/org\\.example\\.androidtunnel/files/runtime/[a-z0-9-]+\\.(conf|json|args)")))
        val args = when (name) {
            "amneziawg-go" -> "awg0"
            "sing-box" -> "run -c ${config ?: error("config required") }"
            "easytier-core" -> {
                val values = File(config ?: error("config required")).readLines().associate { it.substringBefore(' ') to it.substringAfter(' ', "") }
                val network = values["--network-name"] ?: error("network missing")
                val secret = values["--network-secret"] ?: error("secret missing")
                val relay = values["--relay"] ?: error("relay missing")
                require(network.matches(Regex("[A-Za-z0-9_.-]{1,64}")) && secret.matches(Regex("[A-Za-z0-9_./+=-]{1,128}")) && relay.matches(Regex("[A-Za-z0-9.\\[\\]:-]+:[0-9]{1,5}")))
                "--network-name $network --network-secret $secret -p tcp://$relay --need-p2p --latency-first"
            }
            "cloudflared" -> "tunnel --no-autoupdate run --token-file ${config ?: error("config required") }"
            "wstunnel" -> {
                val line = File(config ?: error("config required")).readText().trim()
                require(line.matches(Regex("client --remote wss://[A-Za-z0-9.:-]{1,253}(:[0-9]{1,5})?(/[A-Za-z0-9_./-]*)? tcp://127\\.0\\.0\\.1:22")))
                line
            }
            else -> error("component not allowed")
        }
        val pidFile = "/data/user/0/org.example.androidtunnel/files/runtime/$name.pid"
        val command = "nohup $binary $args >/dev/null 2>&1 </dev/null & echo \$! > $pidFile; sleep 1; kill -0 \$(cat $pidFile)"
        return run(command).exitCode == 0
    }

    fun stopComponent(name: String): Boolean {
        require(name in COMPONENTS)
        val pidFile = "/data/user/0/org.example.androidtunnel/files/runtime/$name.pid"
        val command = "if test -s $pidFile; then kill -TERM \$(cat $pidFile); rm -f $pidFile; fi"
        return run(command).exitCode == 0
    }

    fun stopAllComponents(): Boolean {
        val lines = COMPONENTS.toList().asReversed().map { name ->
            val pidFile = "/data/user/0/org.example.androidtunnel/files/runtime/$name.pid"
            "if test -s $pidFile; then kill -TERM \$(cat $pidFile); rm -f $pidFile; fi"
        } + listOf("ip6tables -D INPUT -i awg0 -j ACCEPT", "ip6tables -D FORWARD -i awg0 -j ACCEPT", "ip link delete awg0")
        return run(lines.joinToString("\n")).exitCode == 0
    }

    fun setupInterface(address: String, allowedIps: String): Boolean {
        require(address.matches(Regex("[0-9A-Fa-f:]+/[0-9]{1,3}")))
        val setup = listOf(
        "ip link show awg0 >/dev/null 2>&1",
        "ip link set dev awg0 mtu 1280",
        "ip -6 addr replace $address dev awg0",
        "ip link set dev awg0 up",
        "ip6tables -C INPUT -i awg0 -j ACCEPT || ip6tables -I INPUT 1 -i awg0 -j ACCEPT",
        "ip6tables -C FORWARD -i awg0 -j ACCEPT || ip6tables -I FORWARD 1 -i awg0 -j ACCEPT"
        )
        if (!setup.all { run(it).exitCode == 0 }) return false
        return allowedIps.split(',').map { it.trim() }.all { cidr ->
            require(cidr.matches(Regex("[0-9A-Fa-f:.]+/[0-9]{1,3}")))
            val route = if (cidr.contains(':')) "ip -6 route replace $cidr dev awg0" else "ip route replace $cidr dev awg0"
            run(route).exitCode == 0
        }
    }

    fun cleanupInterface() {
        runCatching { run("ip6tables -D INPUT -i awg0 -j ACCEPT") }
        runCatching { run("ip6tables -D FORWARD -i awg0 -j ACCEPT") }
        runCatching { run("ip link delete awg0") }
    }

    fun installComponents(files: Map<String, File>): Boolean {
        if (run("mkdir -p /data/local/tmp/android-tunnel").exitCode != 0) return false
        return COMPONENTS.all { name ->
            val source = files[name]?.absolutePath ?: return@all false
            require(source == "/data/user/0/org.example.androidtunnel/files/components/$name")
            run("cp $source /data/local/tmp/android-tunnel/$name && chmod 755 /data/local/tmp/android-tunnel/$name").exitCode == 0
        }
    }

    fun configureAwg(config: String): Boolean {
        require(config.matches(Regex("/data/user/0/org\\.example\\.androidtunnel/files/runtime/amneziawg-go\\.conf")))
        return run("/data/local/tmp/android-tunnel/awg setconf awg0 $config").exitCode == 0
    }

    private val COMPONENTS = setOf("amneziawg-go", "awg", "sing-box", "easytier-core", "cloudflared", "wstunnel")
    private fun isAllowed(command: String): Boolean {
        if (command in ALLOWED) return true
        if (command == "ip link show awg0 >/dev/null 2>&1" ||
            command == "ip6tables -C INPUT -i awg0 -j ACCEPT || ip6tables -I INPUT 1 -i awg0 -j ACCEPT" ||
            command == "ip6tables -C FORWARD -i awg0 -j ACCEPT || ip6tables -I FORWARD 1 -i awg0 -j ACCEPT" ||
            command == "mkdir -p /data/local/tmp/android-tunnel" || command == "ip link set dev awg0 up" ||
            command == "ip6tables -D INPUT -i awg0 -j ACCEPT" || command == "ip6tables -D FORWARD -i awg0 -j ACCEPT" || command == "ip link delete awg0") return true
        if (command.matches(Regex("ip -6 addr replace [0-9A-Fa-f:]+/[0-9]{1,3} dev awg0"))) return true
        if (command.matches(Regex("ip( -6)? route replace [0-9A-Fa-f:.]+/[0-9]{1,3} dev awg0"))) return true
        if (COMPONENTS.any { name -> command == copyCommand(name) }) return true
        if (command.startsWith("nohup ")) {
            return COMPONENTS.any { name ->
                val prefix = "nohup /data/local/tmp/android-tunnel/$name "
                val suffix = " >/dev/null 2>&1 </dev/null & echo \$! > /data/user/0/org.example.androidtunnel/files/runtime/$name.pid; sleep 1; kill -0 \$(cat /data/user/0/org.example.androidtunnel/files/runtime/$name.pid)"
                if (!command.startsWith(prefix) || !command.endsWith(suffix)) return@any false
                val args = command.removePrefix(prefix).removeSuffix(suffix)
                when (name) {
                    "amneziawg-go" -> args == "awg0"
                    "sing-box" -> args == "run -c /data/user/0/org.example.androidtunnel/files/runtime/sing-box.json"
                    "easytier-core" -> args.matches(Regex("--network-name [A-Za-z0-9_.-]{1,64} --network-secret [A-Za-z0-9_./+=-]{1,128} -p tcp://[A-Za-z0-9.\\[\\]:-]+:[0-9]{1,5} --need-p2p --latency-first"))
                    "cloudflared" -> args == "tunnel --no-autoupdate run --token-file /data/user/0/org.example.androidtunnel/files/runtime/cloudflared.conf"
                    else -> args.matches(Regex("client --remote wss://[A-Za-z0-9.:-]{1,253}(:[0-9]{1,5})?(/[A-Za-z0-9_./-]*)? tcp://127\\.0\\.0\\.1:22"))
                }
            }
        }
        return false
    }

    private fun copyCommand(name: String) = "cp /data/user/0/org.example.androidtunnel/files/components/$name /data/local/tmp/android-tunnel/$name && chmod 755 /data/local/tmp/android-tunnel/$name"

    private val ALLOWED = setOf("id -u", "ip link set dev awg0 mtu 1280") +
        COMPONENTS.flatMap { name ->
            val pid = "/data/user/0/org.example.androidtunnel/files/runtime/$name.pid"
            listOf("if test -s $pid; then kill -TERM \$(cat $pid); rm -f $pid; fi")
        } + setOf("/data/local/tmp/android-tunnel/awg setconf awg0 /data/user/0/org.example.androidtunnel/files/runtime/amneziawg-go.conf",
        "cp /data/user/0/org.example.androidtunnel/files/components/amneziawg-go /data/local/tmp/android-tunnel/amneziawg-go && chmod 755 /data/local/tmp/android-tunnel/amneziawg-go",
        "cp /data/user/0/org.example.androidtunnel/files/components/sing-box /data/local/tmp/android-tunnel/sing-box && chmod 755 /data/local/tmp/android-tunnel/sing-box",
        "cp /data/user/0/org.example.androidtunnel/files/components/easytier-core /data/local/tmp/android-tunnel/easytier-core && chmod 755 /data/local/tmp/android-tunnel/easytier-core",
        "cp /data/user/0/org.example.androidtunnel/files/components/cloudflared /data/local/tmp/android-tunnel/cloudflared && chmod 755 /data/local/tmp/android-tunnel/cloudflared",
        "cp /data/user/0/org.example.androidtunnel/files/components/wstunnel /data/local/tmp/android-tunnel/wstunnel && chmod 755 /data/local/tmp/android-tunnel/wstunnel",
        "ip link show awg0 >/dev/null 2>&1",
        "ip6tables -C INPUT -i awg0 -j ACCEPT || ip6tables -I INPUT 1 -i awg0 -j ACCEPT",
        "ip6tables -C FORWARD -i awg0 -j ACCEPT || ip6tables -I FORWARD 1 -i awg0 -j ACCEPT",
        "ip link set dev awg0 up", "ip6tables -D INPUT -i awg0 -j ACCEPT", "ip6tables -D FORWARD -i awg0 -j ACCEPT", "ip link delete awg0")
}
