package io.github.mangome.camftp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import io.github.mangome.camftp.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 单屏：状态 / 主开关 / 相机里要填的读数 / 高级设置（折叠）/ 最近收到。
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
        setAdvancedOpen(savedInstanceState?.getBoolean(KEY_ADVANCED) == true)

        binding.toggleButton.setOnClickListener { toggleService() }
        binding.copyButton.setOnClickListener { copyCameraHint() }
        binding.saveButton.setOnClickListener { saveConfig() }
        binding.anonymousSwitch.setOnCheckedChangeListener { _, checked ->
            binding.userField.isEnabled = !checked   // 匿名登录时用户名密码用不上
            binding.passwordField.isEnabled = !checked
            updateCameraHint()
        }
        binding.hotspotButton.setOnClickListener { openHotspotSettings() }
        binding.selfTestButton.setOnClickListener { runSelfTest() }
        binding.advancedHeader.setOnClickListener { setAdvancedOpen(!binding.advancedBody.isVisible) }

        lifecycleScope.launch {
            FtpState.snapshot.collect { render(it) }
        }
        refreshBestAddress()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ADVANCED, binding.advancedBody.isVisible)
    }

    // 开热点是 App 外面的事，回到前台时重新枚举网卡
    override fun onResume() {
        super.onResume()
        refreshBestAddress()
    }

    private fun render(state: FtpState.Snapshot) {
        running = state.running
        binding.statusText.setText(if (state.running) R.string.status_running else R.string.status_stopped)
        binding.statusDetail.isVisible = state.running
        if (state.running) binding.statusDetail.text = getString(R.string.status_running_detail, state.received)
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (state.running) R.color.cam_status_ok else R.color.cam_status_off)
        )

        binding.toggleButton.setText(if (state.running) R.string.stop else R.string.start)
        // 运行中=「停止接收」用低对比的容器色，别把停止按钮画得和开始一样抢眼
        val (bgAttr, fgAttr) = if (state.running) {
            MaterialR.attr.colorSecondaryContainer to MaterialR.attr.colorOnSecondaryContainer
        } else {
            MaterialR.attr.colorPrimary to MaterialR.attr.colorOnPrimary
        }
        binding.toggleButton.backgroundTintList =
            ColorStateList.valueOf(MaterialColors.getColor(binding.toggleButton, bgAttr))
        binding.toggleButton.setTextColor(MaterialColors.getColor(binding.toggleButton, fgAttr))

        renderEvents(state.events)
        // 本进程内已经有成功入库（真图或自检图）→ 入库链路已被证明，自检按钮收起来
        binding.selfTestButton.isVisible = !state.anyStored
        updateCameraHint()
    }

    private fun renderEvents(events: List<FtpState.Event>) {
        binding.eventEmpty.isVisible = events.isEmpty()
        binding.eventList.isVisible = events.isNotEmpty()
        if (events.isEmpty()) return

        val ok = ContextCompat.getColor(this, R.color.cam_status_ok)
        val fail = ContextCompat.getColor(this, R.color.cam_status_error)
        val dim = MaterialColors.getColor(binding.eventList, MaterialR.attr.colorOnSurfaceVariant)
        val sb = SpannableStringBuilder()
        events.forEachIndexed { i, e ->
            if (i > 0) sb.append("\n")
            var start = sb.length
            sb.append(if (e.ok) "✓ " else "✗ ")
            sb.setSpan(ForegroundColorSpan(if (e.ok) ok else fail), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            start = sb.length
            sb.append(e.name)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (e.detail.isNotBlank()) {
                sb.append("  ")
                start = sb.length
                sb.append(e.detail)
                sb.setSpan(ForegroundColorSpan(dim), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        binding.eventList.text = sb
    }

    private fun refreshBestAddress() {
        bestIface = NetworkInfo.preferred()
        updateCameraHint()
    }

    private fun updateCameraHint() {
        val iface = bestIface
        val anonymous = binding.anonymousSwitch.isChecked

        binding.readingBlock.isVisible = iface != null
        binding.noIpBlock.isVisible = iface == null
        binding.hotspotTag.isVisible = iface?.isHotspot == true
        // 没在热点上就给出口：相机只能连热点，这时候用户要的是设置入口而不是找不到原因
        val hotspotNeeded = iface == null || !iface.isHotspot
        binding.cameraAdvice.isVisible = iface != null && !iface.isHotspot
        binding.hotspotButton.isVisible = hotspotNeeded

        if (iface != null) {
            binding.addressValue.text = iface.ip
            binding.portValue.text = portText()
            binding.userValue.text = binding.userInput.text.toString().ifBlank { Config.user }
            binding.passwordValue.text = binding.passwordInput.text.toString().ifBlank { Config.password }
        }
        binding.userRow.isVisible = !anonymous
        binding.passwordRow.isVisible = !anonymous
        binding.anonymousNote.isVisible = anonymous
    }

    private fun portText() = binding.portInput.text.toString().ifBlank { Config.port.toString() }

    private fun setAdvancedOpen(open: Boolean) {
        binding.advancedBody.isVisible = open
        binding.advancedChevron.animate().rotation(if (open) 180f else 0f).setDuration(160).start()
    }

    private fun toggleService() {
        if (running) {
            startService(Intent(this, FtpService::class.java).setAction(FtpService.ACTION_STOP))
        } else {
            ensureNotificationPermission()
            ContextCompat.startForegroundService(this, Intent(this, FtpService::class.java))
            // 服务起来前先给个说法，别让按钮看起来没反应
            binding.statusText.setText(R.string.status_starting)
            binding.statusDetail.isVisible = false
        }
    }

    private fun copyCameraHint() {
        val iface = bestIface
        if (iface == null) {
            snackbar(getString(R.string.camera_no_ip))
            return
        }
        val text = if (binding.anonymousSwitch.isChecked) {
            getString(R.string.copy_all_anonymous, iface.ip, portText())
        } else {
            getString(
                R.string.copy_all,
                iface.ip,
                portText(),
                binding.userInput.text.toString().ifBlank { Config.user },
                binding.passwordInput.text.toString().ifBlank { Config.password },
            )
        }
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        snackbar(getString(R.string.copied))
    }

    private fun saveConfig() {
        val portText = binding.portInput.text.toString().trim()
        val passiveText = binding.passiveInput.text.toString().trim()
        val user = binding.userInput.text.toString().trim()
        val password = binding.passwordInput.text.toString().trim()
        val folder = binding.folderInput.text.toString().trim()
        val anonymous = binding.anonymousSwitch.isChecked

        var bad: TextInputLayout? = null
        Config.portError(portText)?.let { binding.portField.error = it; bad = binding.portField }
        Config.passivePortsError(passiveText)?.let { binding.passiveField.error = it; bad = bad ?: binding.passiveField }
        if (!anonymous) {
            if (user.isEmpty()) { binding.userField.error = getString(R.string.field_required); bad = bad ?: binding.userField }
            if (password.isEmpty()) { binding.passwordField.error = getString(R.string.field_required); bad = bad ?: binding.passwordField }
        }
        if (folder.isEmpty()) { binding.folderField.error = getString(R.string.field_required); bad = bad ?: binding.folderField }
        if (bad != null) {
            setAdvancedOpen(true)   // 错误提示在折叠区里，用户得看得见
            return
        }

        Config.save(this, portText.toInt(), passiveText, user, password, folder, anonymous)
        Config.load(this)
        fillConfigFields()

        if (running) {
            // 端口/凭据换了，正在跑的服务必须重启才生效
            startService(Intent(this, FtpService::class.java).setAction(FtpService.ACTION_RESTART))
            snackbar(getString(R.string.saved_restart))
        } else {
            snackbar(getString(R.string.saved))
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
        snackbar(getString(R.string.hotspot_failed))
    }

    /**
     * 自检：写一张测试图，直接走 Sink 入库 + 通知链路。
     * 不碰 FTP 层（那层有单测守着），专门排"App 是不是活的"这种问题。
     */
    private fun runSelfTest() {
        snackbar(getString(R.string.self_test_running))
        val folder = binding.folderInput.text.toString().ifBlank { Config.folder }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val file = File(filesDir, "camftp-selftest.jpg")
                drawSelfTestImage().compress(Bitmap.CompressFormat.JPEG, 90, file.outputStream())
                MediaStoreSink(applicationContext, folder).onStored(file)
            }
            // 自检结果也进「最近收到」：成功即收起按钮，失败留在列表里可重试
            FtpState.addEvent(
                FtpState.Event(getString(R.string.self_test_event), result.ok, result.detail, counts = false)
            )
            val text = if (result.ok) {
                getString(R.string.self_test_ok, result.displayName, result.detail)
            } else {
                getString(R.string.self_test_fail, result.detail)
            }
            snackbar(text)
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
        canvas.drawText("CamFtp 测试图", 80f, 380f, paint)
        paint.textSize = 36f
        canvas.drawText("相册里能看到这张图，说明保存正常", 80f, 450f, paint)
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
        binding.passiveInput.setText(Config.passivePorts)
        binding.userInput.setText(Config.user)
        binding.passwordInput.setText(Config.password)
        binding.folderInput.setText(Config.folder)
        binding.anonymousSwitch.isChecked = Config.anonymous
        binding.userField.isEnabled = !Config.anonymous
        binding.passwordField.isEnabled = !Config.anonymous
    }

    private fun snackbar(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).show()
    }

    private companion object {
        const val KEY_ADVANCED = "advanced_open"
    }
}
