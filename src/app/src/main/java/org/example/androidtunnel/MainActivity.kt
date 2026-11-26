package org.example.androidtunnel

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Toast
import android.widget.ToggleButton
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var serviceSwitch: ToggleButton
    private lateinit var menuButton: ImageButton
    private val prefs by lazy { getSharedPreferences("tunnel_status", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var pending = 0 // 1=start requested, 2=stop requested
    private var passwordBusy = false
    private var passwordDialog: AlertDialog? = null
    private var lastConfigError: String? = null
    private var rootReady: Boolean? = null
    private var rootBusy = false
    private var resumed = false
    private var rootWarning: AlertDialog? = null
    private var rootWarningShown = false
    private var guidePending = false
    private var startAfterGuide = false
    private var bootErrorShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pending = savedInstanceState?.getInt("pending", 0) ?: 0
        setContentView(R.layout.activity_main)
        serviceSwitch = findViewById(R.id.service_switch)
        menuButton = findViewById(R.id.menu_button)
        // Keep the visual state derived from the service, not from ToggleButton's automatic flip.
        serviceSwitch.setOnClickListener {
            render()
            if (pending != 0 || passwordBusy || rootBusy) return@setOnClickListener
            val running = prefs.getBoolean("running", false)
            if (running) {
                requestStop()
            } else {
                checkRoot(startAfterCheck = true)
            }
        }
        menuButton.setOnClickListener {
            PopupMenu(this, menuButton).apply {
                menuInflater.inflate(R.menu.main_menu, menu)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.menu_password -> { showPassword(startAfterSave = false); true }
                        R.id.menu_update -> { UpdateClient.checkNow(this@MainActivity); true }
                        else -> false
                    }
                }
            }.show()
        }
        if (pending == 1) scheduleStartTimeout()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("pending", pending)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        resumed = true
        prefs.registerOnSharedPreferenceChangeListener(this)
        render()
        if (rootReady == null && !rootBusy) {
            checkRoot(startAfterCheck = false)
        } else if (rootReady == true && !rootBusy) {
            continueAfterRoot()
        } else if (rootReady == false && !rootBusy) {
            showRootWarning()
        }
        maybeShowConfigFailure()
        maybeShowServiceFailure()
    }
    override fun onPause() {
        resumed = false
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        passwordDialog?.dismiss()
        rootWarning?.setOnDismissListener(null)
        rootWarning?.dismiss()
        super.onDestroy()
    }
    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                render()
                if (key == "config_status") maybeShowConfigFailure()
                if (key == "failure_code" || key == "running") maybeShowServiceFailure()
            }
        }
    }

    private fun render() {
        val running = prefs.getBoolean("running", false)
        val mode = runCatching { TunnelMode.valueOf(prefs.getString("mode", "STOPPED")!!) }.getOrDefault(TunnelMode.STOPPED)
        if (pending == 1 && running) pending = 0
        if (pending == 2 && !running) pending = 0
        if (pending == 1 && !running && mode == TunnelMode.FAILED) pending = 0
        val active = pending != 2 && rootReady != false && running &&
            (mode == TunnelMode.DIRECT_IPV6 || mode == TunnelMode.FALLBACK)
        val label = when {
            pending == 2 -> R.string.service_stopping
            rootBusy && !running -> R.string.service_checking_root
            pending == 1 -> R.string.service_starting
            rootReady == false || (!running && prefs.getString("failure_code", null) == "root_required") -> R.string.service_root_required
            !running && prefs.getString("failure_code", null) == "password_required" -> R.string.service_password_required
            !running && mode == TunnelMode.FAILED -> R.string.service_failed
            !running -> R.string.service_off
            mode == TunnelMode.STARTING -> R.string.service_starting
            mode == TunnelMode.FAILED -> R.string.service_failed
            active -> R.string.service_on
            else -> R.string.service_connecting
        }
        serviceSwitch.isChecked = running || pending != 0
        serviceSwitch.isActivated = active
        serviceSwitch.isEnabled = pending == 0 && !passwordBusy && !rootBusy
        serviceSwitch.text = getString(label)
        // The one large switch conveys both state and next action, including for TalkBack.
        serviceSwitch.contentDescription = "${getString(label)}，${if (running || pending != 0) "关闭服务" else "开启服务"}"
        menuButton.isEnabled = !passwordBusy
    }

    private fun checkRoot(startAfterCheck: Boolean) {
        if (rootBusy) return
        rootBusy = true
        if (startAfterCheck) rootWarningShown = false
        render()
        Thread {
            val granted = RootController.hasRoot()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                rootBusy = false
                rootReady = granted
                if (!granted) {
                    render()
                    showRootWarning()
                    return@runOnUiThread
                }
                rootWarningShown = false
                if (!prefs.getBoolean("running", false) && prefs.getString("failure_code", null) == "root_required") {
                    prefs.edit().remove("failure_code").putString("mode", TunnelMode.STOPPED.name).apply()
                }
                render()
                // Do not access the relay or generate keys on a device without root.
                if (startupChecked.compareAndSet(false, true)) Thread {
                    RelayRepository.purgeLegacy(applicationContext)
                    runCatching { KeyRepository.prepare(applicationContext, false) }
                    if (!prefs.getBoolean("running", false)) {
                        val selected = runCatching { RelayRepository.settings(applicationContext) }.getOrNull()
                        if (selected != null && selected.password.isNotBlank()) {
                            runCatching { RelayRepository.prepareStartup(applicationContext, selected) }
                        }
                    }
                }.start()
                guidePending = true
                if (startAfterCheck && resumed) {
                    // First successful root grant may require an OEM settings visit.
                    // Do not start the tunnel behind that permission page.
                    if (AutoStartGuide.openOnce(this)) {
                        guidePending = false
                        startAfterGuide = true
                        return@runOnUiThread
                    }
                    guidePending = false
                    val hasPassword = runCatching { RelayRepository.settings(this).password.isNotBlank() }.getOrDefault(false)
                    if (hasPassword) requestStart() else showPassword(startAfterSave = true)
                    UpdateClient.checkOnce(this)
                } else {
                    continueAfterRoot()
                }
            }
        }.start()
    }

    private fun continueAfterRoot() {
        if (!resumed || isFinishing || isDestroyed) return
        if (guidePending) {
            guidePending = false
            if (AutoStartGuide.openOnce(this)) return
        }
        if (startAfterGuide) {
            startAfterGuide = false
            // Re-check after returning from OEM settings instead of trusting an old grant.
            checkRoot(startAfterCheck = true)
            return
        }
        UpdateClient.checkOnce(this)
    }

    private fun showRootWarning() {
        if (!resumed || rootWarningShown || isFinishing || isDestroyed) return
        rootWarningShown = true
        rootWarning = AlertDialog.Builder(this)
            .setTitle(R.string.root_required_title)
            .setMessage(R.string.root_required_message)
            .setPositiveButton("确定", null)
            .create().apply {
                setOnDismissListener {
                    rootWarning = null
                    if (resumed && !isDestroyed && !isFinishing) UpdateClient.checkOnce(this@MainActivity)
                }
                show()
            }
    }

    private fun maybeShowServiceFailure() {
        if (prefs.getBoolean("running", false) || !resumed || rootBusy) return
        when (prefs.getString("failure_code", null)) {
            "root_required" -> {
                rootReady = false
                render()
                showRootWarning()
            }
            "boot_blocked" -> if (!bootErrorShown) {
                bootErrorShown = true
                toast("系统阻止开机自启，请允许清连后台自启动")
            }
        }
    }

    private fun requestStart() {
        if (pending != 0 || prefs.getBoolean("running", false)) return
        if (rootReady != true) { checkRoot(startAfterCheck = true); return }
        // Remove a previous failed-start result so it cannot cancel this new transition.
        prefs.edit().remove("failure_code").putString("mode", TunnelMode.STOPPED.name).apply()
        pending = 1
        render()
        runCatching { TunnelService.start(this) }.onFailure {
            pending = 0
            render()
            toast("无法开启服务")
        }
        if (pending == 1) {
            scheduleStartTimeout()
            // Android's system permission dialog is needed for a visible foreground notification.
            // It is requested only after the user chooses to enable the service.
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                notificationAsked.compareAndSet(false, true)) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 21)
            }
        }
    }

    private fun scheduleStartTimeout() {
        handler.postDelayed({
            if (pending == 1 && !prefs.getBoolean("running", false)) {
                pending = 0
                render()
                toast("服务未能启动")
            }
        }, 15000L)
    }

    private fun requestStop() {
        if (pending == 2) return
        pending = 2
        // Do not render (and clear pending) before the stop request has been queued.
        serviceSwitch.isChecked = true
        serviceSwitch.isActivated = false
        serviceSwitch.isEnabled = false
        serviceSwitch.text = getString(R.string.service_stopping)
        runCatching { TunnelService.stop(this) }.onFailure {
            pending = 0
            render()
            toast("无法关闭服务")
        }
    }

    private fun showPassword(startAfterSave: Boolean) {
        if (passwordBusy || passwordDialog?.isShowing == true || pending != 0) return
        val view = layoutInflater.inflate(R.layout.dialog_password, null)
        val input = view.findViewById<EditText>(R.id.relay_password)
        input.setText(runCatching { RelayRepository.settings(this).password }.getOrDefault(""))
        input.setSelection(input.text.length)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.menu_password)
            .setView(view).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        passwordDialog = dialog
        dialog.setOnDismissListener { if (passwordDialog === dialog) passwordDialog = null }
        dialog.show()
        // A fresh empty dialog may be captured for emulator evidence. Once a secret is
        // present, Android blocks screenshots of the password dialog.
        if (input.text.isNotEmpty()) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrEmpty()) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val candidate = try { V2.password(input.text.toString()) }
            catch (_: Exception) { input.error = "请输入口令"; return@setOnClickListener }
            if (passwordBusy) return@setOnClickListener
            passwordBusy = true
            dialog.setCancelable(false)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply { isEnabled = false; text = "验证中" }
            input.isEnabled = false
            render()
            Thread {
                val result = runCatching { RelayRepository.changePassword(applicationContext, candidate) }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    passwordBusy = false
                    render()
                    if (result.isSuccess) {
                        lastConfigError = null
                        dialog.dismiss()
                        if (startAfterSave) requestStart()
                        else toast(if (prefs.getBoolean("running", false)) "已保存，下次开启生效" else "已保存")
                    } else {
                        dialog.setCancelable(true)
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply { isEnabled = true; text = "保存" }
                        input.isEnabled = true
                        input.error = "验证失败，请检查口令或网络"
                    }
                }
            }.start()
        }
    }

    private fun maybeShowConfigFailure() {
        val message = prefs.getString("config_status", "").orEmpty()
        if (!message.startsWith("配置拉取失败")) { lastConfigError = null; return }
        if (message != lastConfigError) {
            lastConfigError = message
            if (!passwordBusy) toast("配置拉取失败，请检查口令或网络")
        }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    companion object {
        private val startupChecked = AtomicBoolean(false)
        private val notificationAsked = AtomicBoolean(false)
    }
}
