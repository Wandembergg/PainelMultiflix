package com.lumina.soltv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.lumina.soltv.LuminaApplication
import com.lumina.soltv.databinding.ActivityBootstrapBinding
import com.lumina.soltv.util.DeviceId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BootstrapActivity : ComponentActivity() {
    private lateinit var b: ActivityBootstrapBinding
    private val repo get() = (application as LuminaApplication).repository
    private var watchJob: Job? = null
    private val mac by lazy { DeviceId.panelMac(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBootstrapBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.deviceId.text = "MAC / ID: $mac"
        b.retry.setOnClickListener { load(true) }
        load(true)
    }

    private fun load(startWatcher: Boolean) {
        b.retry.visibility = View.GONE
        b.progress.visibility = View.VISIBLE
        b.activationBox.visibility = View.GONE
        b.status.text = "Conectando ao painel Lumina..."
        lifecycleScope.launch {
            runCatching { repo.bootstrap() }
                .onSuccess { (auth, playlist) ->
                    if (auth.portals.isNotEmpty() && playlist.channels.isNotEmpty()) {
                        watchJob?.cancel()
                        startActivity(Intent(this@BootstrapActivity, MainActivity::class.java))
                        finish()
                    } else showActivation(auth.deviceKey, startWatcher)
                }
                .onFailure {
                    b.progress.visibility = View.GONE
                    b.retry.visibility = View.VISIBLE
                    b.activationBox.visibility = View.VISIBLE
                    b.activationCode.text = mac
                    b.status.text = "Painel indisponível. Verifique a conexão e tente novamente."
                }
        }
    }

    private fun showActivation(deviceKey: String, startWatcher: Boolean) {
        b.progress.visibility = View.GONE
        b.activationBox.visibility = View.VISIBLE
        b.retry.visibility = View.VISIBLE
        b.activationCode.text = mac
        b.deviceKey.text = if (deviceKey.isNotBlank()) "Código do painel: $deviceKey" else ""
        b.status.text = "Sem playlist. Cadastre este MAC no painel e associe DNS + usuário + senha."
        if (startWatcher && watchJob?.isActive != true) {
            watchJob = lifecycleScope.launch {
                while (isActive) {
                    delay(10_000)
                    runCatching { repo.bootstrap() }.onSuccess { (auth, playlist) ->
                        if (auth.portals.isNotEmpty() && playlist.channels.isNotEmpty()) {
                            startActivity(Intent(this@BootstrapActivity, MainActivity::class.java))
                            finish()
                            cancel()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        watchJob?.cancel()
        super.onDestroy()
    }
}
