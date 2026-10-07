package io.github.mangome.camftp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.appcompat.app.AlertDialog
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单屏：状态 / 相机里要填的读数 / 高级设置（折叠）/ 最近收到。
 * 没有开始/停止按钮：接收跟着热点走（见 [HotspotWatch]），关热点就是停止。
 * 不做多页面、不做 Compose（文档 §5.2）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var running = false
    private var bestIface: NetworkInfo.Iface? = null
    private var ifaces: List<NetworkInfo.Iface> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Config.load(this)
        fillConfigFields()
        setAdvancedOpen(savedInstanceState?.getBoolean(KEY_ADVANCED) == true)

        binding.saveButton.setOnClickListener { saveConfig() }
        binding.anonymousSwitch.setOnCheckedChangeListener { _, checked ->
            binding.userField.isEnabled = !checked   // 匿名登录时用户名密码用不上
            binding.passwordField.isEnabled = !checked
            updateCameraHint()
        }
        binding.hotspotButton.setOnClickListener { openHotspotSettings() }
        binding.selfTestButton.setOnClickListener { runSelfTest() }
        binding.aboutButton.setOnClickListener { showAbout() }
        binding.advancedHeader.setOnClickListener { setAdvancedOpen(!binding.advancedBody.isVisible) }

        lifecycleScope.launch {
            FtpState.snapshot.collect { render(it) }
        }
        HotspotWatch.attach(this)
        ensureNotificationPermission()   // 常驻通知是「正在接收」的唯一指示，第一次进来就问
        refreshBestAddress()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ADVANCED, binding.advancedBody.isVisible)
    }

    // 开热点是 App 外面的事，回到前台时重新枚举网卡 + 把服务对齐到热点状态
    override fun onResume() {
        super.onResume()
        refreshBestAddress()
        HotspotWatch.sync(this)   // 热点开着没在收（后台没起成服务、进程刚回来）就在这儿补上
    }

    private fun render(state: FtpState.Snapshot) {
        val wasRunning = running
        running = state.running
        // 服务起停多半是热点变了引起的（用户在设置里关的热点 / 热点超时自己关）：网卡重扫一遍，
        // 否则地址和「热点」标记会停在旧状态，等下次回前台才对上
        if (wasRunning != state.running) refreshBestAddress()

        // 状态行文字由 updateCameraHint() 唯一负责（它才知道有没有热点），这里只管第二行
        binding.statusDetail.isVisible = state.running
        if (state.running) binding.statusDetail.text = cameraStatusText(state)
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (state.running) R.color.cam_status_ok else R.color.cam_status_off)
        )

        renderEvents(state.events)
        // 本进程内已经有成功入库（真图或自检图）→ 入库链路已被证明，自检按钮收起来
        binding.selfTestButton.isVisible = !state.anyStored
        updateCameraHint()
    }

    /** 状态行第二行：相机连上了没（会话数）+ 已收到张数 */
    private fun cameraStatusText(state: FtpState.Snapshot): String = when {
        state.clients > 0 -> getString(R.string.status_camera_online, state.received)
        state.lastConnectAt == 0L -> getString(R.string.status_camera_waiting, state.received)
        else -> getString(
            R.string.status_camera_offline,
            DateFormat.getTimeFormat(this).format(Date(state.lastConnectAt)),
            state.received,
        )
    }

    /**
     * 事件行的时间戳用固定 24 小时制 [SimpleDateFormat]，不走 `android.text.format.DateFormat`：
     * 12/24 小时制在 ROM 上的处理不一致（§5 的字体度量那个坑同源）。到秒 —— 连拍几张都落在
     * 同一分钟里，只到分钟分不出先后；列表只有 10 条、看的是「刚刚收到没」，跨零点看不出是哪天
     */
    private val eventTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
            sb.append(eventTime.format(Date(e.at)))
            sb.append("  ")
            sb.setSpan(ForegroundColorSpan(dim), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            start = sb.length
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
        ifaces = NetworkInfo.ipv4(this)             // 已按「热点优先」排好序
        bestIface = ifaces.firstOrNull()
        updateCameraHint()
    }

    private fun updateCameraHint() {
        val hotspotIface = bestIface?.takeIf { it.isHotspot }
        val anonymous = binding.anonymousSwitch.isChecked

        // 前提条件：相机只能连热点。没热点时状态行说的就是「需要开启热点」
        // （原来另有一张错误色警示卡，跟这行是同一件事，已删）
        binding.statusText.setText(
            when {
                hotspotIface == null -> R.string.status_need_hotspot
                running -> R.string.status_running
                else -> R.string.status_stopped
            }
        )

        // 不在热点上：相机用不了这个地址，就别把 IP 摆成主角
        // （「打开热点设置」按钮常驻在状态行下面，不受这里影响）
        binding.readingBlock.isVisible = hotspotIface != null
        binding.noIpBlock.isVisible = hotspotIface == null
        binding.hotspotTag.isVisible = hotspotIface != null
        binding.localAddresses.isVisible = hotspotIface == null && ifaces.isNotEmpty()
        if (hotspotIface == null && ifaces.isNotEmpty()) {
            binding.localAddresses.text =
                getString(R.string.camera_other_addresses, ifaces.joinToString(" ") { "${it.ip}(${it.name})" })
        }

        hotspotIface?.let {
            binding.addressValue.text = it.ip
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

    private fun saveConfig() {
        val portText = binding.portInput.text.toString().trim()
        val user = binding.userInput.text.toString().trim()
        val password = binding.passwordInput.text.toString().trim()
        val folder = binding.folderInput.text.toString().trim()
        val anonymous = binding.anonymousSwitch.isChecked

        var bad: TextInputLayout? = null
        Config.portError(portText)?.let { binding.portField.error = it; bad = binding.portField }
        if (!anonymous) {
            if (user.isEmpty()) { binding.userField.error = getString(R.string.field_required); bad = bad ?: binding.userField }
            if (password.isEmpty()) { binding.passwordField.error = getString(R.string.field_required); bad = bad ?: binding.passwordField }
        }
        if (folder.isEmpty()) { binding.folderField.error = getString(R.string.field_required); bad = bad ?: binding.folderField }
        if (bad != null) {
            setAdvancedOpen(true)   // 错误提示在折叠区里，用户得看得见
            return
        }

        Config.save(this, portText.toInt(), user, password, folder, anonymous)
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

    /** 关于：版权 / 版本 / 开源信息，源码走系统浏览器 */
    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about)
            .setMessage(getString(R.string.about_body, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.about_close, null)
            .setNeutralButton(R.string.about_source) { _, _ -> openRepository() }
            .show()
    }

    private fun openRepository() {
        val url = getString(R.string.about_source_url)
        val opened = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isSuccess
        if (!opened) snackbar(getString(R.string.about_source_failed, url))
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
