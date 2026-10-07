package io.github.mangome.camftp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.mangome.camftp.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/** M2 的最小壳：一个开关 + 一行状态。M3 在这里扩成正式界面。 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toggleButton.setOnClickListener {
            if (running) {
                startService(Intent(this, FtpService::class.java).setAction(FtpService.ACTION_STOP))
            } else {
                ensureNotificationPermission()
                ContextCompat.startForegroundService(this, Intent(this, FtpService::class.java))
            }
        }

        lifecycleScope.launch {
            FtpState.snapshot.collect { render(it) }
        }
    }

    private fun render(state: FtpState.Snapshot) {
        running = state.running
        binding.statusText.text = if (state.running) {
            getString(R.string.status_running, state.port, state.received)
        } else {
            getString(R.string.status_stopped)
        }
        binding.toggleButton.setText(if (state.running) R.string.stop else R.string.start)
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
