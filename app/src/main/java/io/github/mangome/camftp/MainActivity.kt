package io.github.mangome.camftp

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.LruCache
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.Toast
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
import io.github.mangome.camftp.databinding.ItemRecentBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单屏：相机连接面板（主角）/ 相机里要填的读数 / 高级设置（折叠）/ 最近收到。
 * 没有开始/停止按钮：接收跟着热点走（见 [HotspotWatch]），关热点就是停止。
 * 不做多页面、不做 Compose（见 handoff §0「别做的」）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var running = false
    private var bestIface: NetworkInfo.Iface? = null
    private var ifaces: List<NetworkInfo.Iface> = emptyList()

    /** 最后一次快照：连接面板的文字靠它算（[updateCameraHint] 不只被 collect 叫） */
    private var state = FtpState.Snapshot()

    /** 面板那颗灯的呼吸动画：连上才转，[lampOn] 挡重复重启 */
    private var lamp: ObjectAnimator? = null
    private var lampOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Config.load(this)
        FtpState.attach(filesDir)   // collect 之前读回落盘的「最近收到」，首帧就是完整的
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

    override fun onDestroy() {
        lamp?.cancel()   // 无限循环的动画握着 View，别让它比 Activity 活得久
        super.onDestroy()
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
        this.state = state
        // 服务起停多半是热点变了引起的（用户在设置里关的热点 / 热点超时自己关）：网卡重扫一遍，
        // 否则地址和「热点」标记会停在旧状态，等下次回前台才对上
        if (wasRunning != state.running) refreshBestAddress()

        // 连接面板的文字由 updateCameraHint() 唯一负责（它才知道有没有热点），这里只管事件列表
        updateCameraHint()
        renderEvents(state.events)
        // 本进程内已经有成功入库（真图或自检图）→ 入库链路已被证明，自检按钮收起来
        binding.selfTestButton.isVisible = !state.anyStored
    }

    /** 副行的时间戳：同样固定 24 小时制 [SimpleDateFormat]（理由见 [eventTime]） */
    private val panelClock = SimpleDateFormat("HH:mm", Locale.getDefault())

    /**
     * 事件行的时间戳用固定 24 小时制 [SimpleDateFormat]，不走 `android.text.format.DateFormat`：
     * 12/24 小时制在 ROM 上的处理不一致（handoff §4 的字体度量那个坑同源）。到秒 —— 连拍几张都落在
     * 同一分钟里，只到分钟分不出先后。条目是落盘的，重启后列表里可能是前几天收的：非今天带上
     * 月日，否则「18:23:45」看着像刚刚收到
     */
    private val eventTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val eventDateTime = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    /** 网格间距 8dp：行内格间距和行间距共用一个值。lazy：字段初始化早于 attachBaseContext，那时拿不到 resources */
    private val gridGap by lazy { (8 * resources.displayMetrics.density).toInt() }

    /** 解出来的小图按 uri 缓存：12 张 384px 约 7MB，8MB 上限挡住无界增长 */
    private val thumbCache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /**
     * 「最近收到」= 成功条目的缩略图网格 + 失败条目的日志小字。
     * 成功条目不再显示时间和目录（目录就是 `DCIM/<配置的目录>`，每行都一样；图本身带着「刚拍的」的时间感），
     * 失败条目没有图，时间和原因照旧 —— 那是「相机传了但没进相册」的唯一线索。
     */
    private fun renderEvents(events: List<FtpState.Event>) {
        binding.eventEmpty.isVisible = events.isEmpty()
        renderGrid(events.filter { it.ok })
        renderFailures(events.filter { !it.ok })
    }

    /** 3 列方格，新的在前。整块重建：最多 12 格，比维护视图复用省事得多 */
    private fun renderGrid(events: List<FtpState.Event>) {
        binding.eventGrid.isVisible = events.isNotEmpty()
        binding.eventGrid.removeAllViews()
        events.chunked(GRID_COLUMNS).forEach { row ->
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { column, event ->
                val tile = ItemRecentBinding.inflate(layoutInflater, rowView, false)
                bindTile(tile, event)
                rowView.addView(
                    tile.root,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = if (column > 0) gridGap else 0
                    },
                )
            }
            // 最后一行不满时补空位，否则那几个格子会被拉宽，跟上面几行对不齐
            repeat(GRID_COLUMNS - row.size) {
                rowView.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = gridGap })
            }
            binding.eventGrid.addView(
                rowView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = if (binding.eventGrid.childCount > 0) gridGap else 0 },
            )
        }
    }

    private fun bindTile(tile: ItemRecentBinding, event: FtpState.Event) {
        tile.tileCaption.text = event.name
        tile.tileImage.isVisible = event.thumb != null
        tile.tileBadge.isVisible = event.thumb == null
        if (event.thumb == null) {
            // 出不了图（RAW 抽不出预览 / 原图已删 / 非媒体文件）：后缀比通用破图图标说得清楚
            tile.tileBadge.text = event.name.substringAfterLast('.', "").uppercase().ifEmpty { "FILE" }
        } else {
            showThumb(event, tile.tileImage)
        }
        // 整块是热区（跟原来「整行可点」一个意思）。图在相册里被删了也能点，点了给提示（见 openStored）
        tile.tileCard.contentDescription = event.name
        tile.tileCard.isClickable = event.uri != null
        tile.tileCard.setOnClickListener { event.uri?.let(::openStored) }
    }

    /**
     * 小图直接存在事件里（[FtpState.Event.thumb]），解码丢到 IO 线程。
     * 解回来时格子可能已经被下一次 render 换掉了（每次入库都会整块重建）：认 tag，不认 view
     */
    private fun showThumb(event: FtpState.Event, view: ImageView) {
        val uri = event.uri ?: return
        thumbCache.get(uri)?.let { view.setImageBitmap(it); return }
        view.tag = uri
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                event.thumb?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            } ?: return@launch
            thumbCache.put(uri, bitmap)
            if (view.tag == uri) view.setImageBitmap(bitmap)
        }
    }

    /** 失败条目：没有图，退回原来的日志小字（等宽 + ✗ + 语义色；它们不合进网格） */
    private fun renderFailures(events: List<FtpState.Event>) {
        binding.eventLog.isVisible = events.isNotEmpty()
        if (events.isEmpty()) {
            binding.eventLog.text = null
            return
        }

        val fail = ContextCompat.getColor(this, R.color.cam_status_error)
        val dim = MaterialColors.getColor(binding.eventLog, MaterialR.attr.colorOnSurfaceVariant)
        val sb = SpannableStringBuilder()
        events.forEachIndexed { i, e ->
            if (i > 0) sb.append("\n")
            var start = sb.length
            val stamp = if (DateUtils.isToday(e.at)) eventTime else eventDateTime
            sb.append(stamp.format(Date(e.at)))
            sb.append("  ")
            sb.setSpan(ForegroundColorSpan(dim), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            start = sb.length
            sb.append("✗ ")
            sb.setSpan(ForegroundColorSpan(fail), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        binding.eventLog.text = sb
    }

    /**
     * 点「最近收到」里的一行 → 交给系统默认应用打开刚入库的那张图。
     * 图可能已经在相册里被删了：那条 URI 照样能开出查看器（它自己空白页或报错），所以先探一下
     * MediaStore 还能不能打开，删了就就地提示，别开个空页
     */
    private fun openStored(uri: String) {
        val target = Uri.parse(uri)
        val alive = runCatching {
            contentResolver.openAssetFileDescriptor(target, "r")?.use { true } ?: false
        }.getOrDefault(false)
        if (!alive) {
            Toast.makeText(this, getString(R.string.event_gone), Toast.LENGTH_SHORT).show()
            return
        }
        // 带上授读标志：图是本 App 插进 MediaStore 的，相册等的读权限靠这条临时授予
        val intent = Intent(Intent.ACTION_VIEW, target)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (runCatching { startActivity(intent) }.isFailure) snackbar(getString(R.string.event_open_failed))
    }

    private fun refreshBestAddress() {
        ifaces = NetworkInfo.ipv4(this)             // 已按「热点优先」排好序
        bestIface = ifaces.firstOrNull()
        updateCameraHint()
    }

    private fun updateCameraHint() {
        val hotspotIface = bestIface?.takeIf { it.isHotspot }
        val connected = state.running && state.clients > 0
        val transferring = state.transferring
        val anonymous = binding.anonymousSwitch.isChecked

        // 前提条件：相机只能连热点。没热点时面板说的就是「需要开启热点」
        // （原来另有一张错误色警示卡，跟这行是同一件事，已删）
        binding.statusText.setText(
            when {
                hotspotIface == null -> R.string.status_need_hotspot
                transferring != null -> R.string.status_transferring
                connected -> R.string.status_camera_online
                state.lastConnectAt == 0L -> R.string.status_camera_waiting
                else -> R.string.status_camera_offline
            }
        )
        // 字色 / 面板底色只三个语义：连上、传输中=绿（跟灯同色）+ 暗绿底，等 / 断开=亮字，缺热点=暗字 + 暗红底。
        // 分支顺序跟上一段一致：没热点时即使还挂着残留会话也不报绿。
        // 底色只换色相不换明度 —— 面板在两个主题下都得是暗块（handoff §2.26、colors.xml）
        val (panelColor, textColor) = when {
            hotspotIface == null -> R.color.cam_instrument_alert to R.color.cam_on_instrument_variant
            transferring != null || connected -> R.color.cam_instrument_live to R.color.cam_link_on
            else -> R.color.cam_instrument to R.color.cam_on_instrument
        }
        binding.linkPanel.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, panelColor))
        binding.statusText.setTextColor(ContextCompat.getColor(this, textColor))

        // 副行：传输中报文件名，否则只在「断开但连过」时说「上次连接」，其他状态不占位
        val offlineSince = state.running && !connected && state.lastConnectAt > 0L
        binding.statusDetail.isVisible = transferring != null || offlineSince
        binding.statusDetail.text = when {
            transferring != null -> transferring
            offlineSince ->
                getString(R.string.status_detail_offline, panelClock.format(Date(state.lastConnectAt)))
            else -> ""
        }
        setLinkLamp(connected)

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

    /**
     * 面板左上角那颗灯：相机在对面就呼吸（相机机身那颗传输灯的意思），否则常暗。
     * [lampOn] 挡重复调用 —— render() 每次状态变化都走到这儿，动画不能每次都重新开始。
     * 关掉动画的机器（开发者选项里动画缩放=0）就常亮不呼吸。
     */
    private fun setLinkLamp(on: Boolean) {
        if (on == lampOn) return
        lampOn = on
        lamp?.cancel()
        lamp = null
        binding.statusDot.alpha = 1f
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (on) R.color.cam_link_on else R.color.cam_link_off)
        )
        if (on && ValueAnimator.areAnimatorsEnabled()) {
            lamp = ObjectAnimator.ofFloat(binding.statusDot, "alpha", 1f, 0.25f).apply {
                duration = 1100
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

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
            // 自检结果也进「最近收到」：成功即收起按钮，失败留在列表里可重试。
            // 带上 uri，跟真图一样能点开（自检图也可能被用户在相册里删掉，那条路要走到同一个提示）
            FtpState.addEvent(
                FtpState.Event(
                    getString(R.string.self_test_event), result.ok, result.detail,
                    counts = false, uri = result.uri, thumb = result.thumb,
                )
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

        /** 网格列数：跟卡片圆角、间距一起构成「最近收到」的几何，改这里就够 */
        const val GRID_COLUMNS = 3
    }
}
