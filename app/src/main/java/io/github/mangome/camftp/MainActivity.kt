package io.github.mangome.camftp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.mangome.camftp.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 单屏：状态 / 开关 / 相机里要填的地址 / 配置 / 最近事件。
 * 不做多页面、不做 Compose（文档 §5.2）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var running = false
    private var bestIface: NetworkInfo.Iface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Config.load(this)
        fillConfigFields()

        binding.toggleButton.setOnClickListener { toggleService() }
        binding.copyButton.setOnClickListener { copyCameraHint() }
        binding.saveButton.setOnClickListener { saveConfig() }
        binding.anonymousCheck.setOnCheckedChangeListener { _, checked ->
            binding.userInput.isEnabled = !checked   // 匿名登录时用户名密码用不上
            binding.passwordInput.isEnabled = !checked
            updateCameraHint()
        }
        binding.hotspotButton.setOnClickListener { openHotspotSettings() }
        binding.selfTestButton.setOnClickListener { runSelfTest() }

        lifecycleScope.launch {
            FtpState.snapshot.collect { render(it) }
        }
        refreshBestAddress()
    }

    // 开热点是 App 外面的事，回到前台时重新枚举网卡
    override fun onResume() {
        super.onResume()
        refreshBestAddress()
    }

    private fun render(state: FtpState.Snapshot) {
        running = state.running
        binding.statusText.text = if (state.running) {
            getString(R.string.status_running, state.port, state.received)
        } else {
            getString(R.string.status_stopped)
        }
        binding.toggleButton.setText(if (state.running) R.string.stop else R.string.start)

        binding.eventList.text = if (state.events.isEmpty()) {
            getString(R.string.event_none)
        } else {
            state.events.joinToString("\n") { e ->
                if (e.ok) getString(R.string.event_ok, e.name, e.detail)
                else getString(R.string.event_fail, e.name, e.detail)
            }
        }
        updateCameraHint()
    }

    private fun refreshBestAddress() {
        bestIface = NetworkInfo.preferred()
        updateCameraHint()
    }

    private fun updateCameraHint() {
        val iface = bestIface
        if (iface == null) {
            binding.cameraHint.text = getString(R.string.camera_hint_no_ip)
            return
        }
        val addr = if (iface.isHotspot) getString(R.string.ip_hotspot_suffix, iface.ip) else iface.ip
        val port = binding.portInput.text.toString().ifBlank { Config.port.toString() }
        binding.cameraHint.text = if (binding.anonymousCheck.isChecked) {
            getString(R.string.camera_hint_anonymous, addr, port)
        } else {
            getString(
                R.string.camera_hint,
                addr,
                port,
                binding.userInput.text.toString().ifBlank { Config.user },
                binding.passwordInput.text.toString().ifBlank { Config.password },
            )
        }
    }

    private fun toggleService() {
        if (running) {
            startService(Intent(this, FtpService::class.java).setAction(FtpService.ACTION_STOP))
        } else {
            ensureNotificationPermission()
            ContextCompat.startForegroundService(this, Intent(this, FtpService::class.java))
        }
    }

    private fun copyCameraHint() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.section_camera_title), binding.cameraHint.text))
        toast(getString(R.string.copied))
    }

    private fun saveConfig() {
        val portText = binding.portInput.text.toString().trim()
        val passiveText = binding.passivePortsInput.text.toString().trim()
        val user = binding.userInput.text.toString().trim()
        val password = binding.passwordInput.text.toString().trim()
        val folder = binding.folderInput.text.toString().trim()
        val anonymous = binding.anonymousCheck.isChecked

        var bad: EditText? = null
        Config.portError(portText)?.let { binding.portInput.error = it; bad = binding.portInput }
        Config.passivePortsError(passiveText)?.let { binding.passivePortsInput.error = it; bad = bad ?: binding.passivePortsInput }
        if (!anonymous) {
            if (user.isEmpty()) { binding.userInput.error = getString(R.string.field_required); bad = bad ?: binding.userInput }
            if (password.isEmpty()) { binding.passwordInput.error = getString(R.string.field_required); bad = bad ?: binding.passwordInput }
        }
        if (folder.isEmpty()) { binding.folderInput.error = getString(R.string.field_required); bad = bad ?: binding.folderInput }
        if (bad != null) return

        Config.save(this, portText.toInt(), passiveText, user, password, folder, anonymous)
        Config.load(this)

        if (running) {
            // 端口/凭据换了，正在跑的服务必须重启才生效
            startService(Intent(this, FtpService::class.java).setAction(FtpService.ACTION_RESTART))
            toast(getString(R.string.saved_restart))
        } else {
            toast(getString(R.string.saved))
        }
        updateCameraHint()
    }

    private fun openHotspotSettings() {
        // 真机实测（ColorOS 17）四个候选的真实落点：
        //   com.android.settings.TETHER_SETTINGS      无 App 注册 → 启动失败
        //   android.settings.TETHER_SETTINGS          “网络共享”页（还得再点一下）
        //   com.android.settings.WIFI_TETHER_SETTINGS “个人热点”页 ✔
        //   Panel.ACTION_INTERNET_CONNECTIVITY        SystemUI 未注册（只有音量面板）
        //   ACTION_WIRELESS_SETTINGS                  网络/WiFi 首页（旧代码就落在这里）
        val intents = listOf(
            Intent("com.android.settings.WIFI_TETHER_SETTINGS"),
            Intent("android.settings.TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        toast(getString(R.string.hotspot_failed))
    }

    /**
     * 自检：写一张测试图，直接走 Sink 入库 + 通知链路。
     * 不碰 FTP 层（那层有单测守着），专门排"App 是不是活的"这种问题。
     */
    private fun runSelfTest() {
        toast(getString(R.string.self_test_running))
        val folder = binding.folderInput.text.toString().ifBlank { Config.folder }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val file = File(filesDir, "camftp-selftest.jpg")
                drawSelfTestImage().compress(Bitmap.CompressFormat.JPEG, 90, file.outputStream())
                MediaStoreSink(applicationContext, folder).onStored(file)
            }
            val text = if (result.ok) {
                getString(R.string.self_test_ok, result.displayName, result.detail)
            } else {
                getString(R.string.self_test_fail, result.detail)
            }
            toast(text)
        }
    }

    private fun drawSelfTestImage(): Bitmap {
        val bmp = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(18, 48, 72))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 56f
        }
        canvas.drawText("CamFtp 自检图", 80f, 380f, paint)
        paint.textSize = 36f
        canvas.drawText("能看到这张图 = 入库链路正常", 80f, 450f, paint)
        return bmp
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun fillConfigFields() {
        binding.portInput.setText(Config.port.toString())
        binding.passivePortsInput.setText(Config.passivePorts)
        binding.userInput.setText(Config.user)
        binding.passwordInput.setText(Config.password)
        binding.folderInput.setText(Config.folder)
        binding.anonymousCheck.isChecked = Config.anonymous
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
