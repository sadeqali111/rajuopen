package com.ovpn.autodelay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ovpn.autodelay.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val zipPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { stream ->
                    VpnStateBus.loadedProfiles = OvpnConfigParser.parseFromZip(stream)
                }
                withContext(Dispatchers.Main) {
                    val count = VpnStateBus.loadedProfiles.size
                    binding.tvStatus.text = "Loaded $count unique servers."
                    VpnStateBus.log("Imported $count servers from ZIP (TunnelBear -> TCP 7011 SplitRelay).")
                }
            }
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnTestService()
        } else {
            VpnStateBus.log("VPN permission denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("creds", Context.MODE_PRIVATE)
        binding.etUsername.setText(prefs.getString("u", ""))
        binding.etPassword.setText(prefs.getString("p", ""))

        lifecycleScope.launch {
            VpnStateBus.statusText.collect { binding.tvStatus.text = it }
        }
        lifecycleScope.launch {
            VpnStateBus.logs.collect { line ->
                binding.tvLogs.append("$line\n")
                binding.scrollLogs.post { binding.scrollLogs.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }

        binding.btnImportZip.setOnClickListener {
            zipPickerLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }

        binding.btnStartTest.setOnClickListener {
            val u = binding.etUsername.text.toString().trim()
            val p = binding.etPassword.text.toString().trim()
            if (u.isEmpty() || p.isEmpty()) {
                VpnStateBus.log("Enter username and password first.")
                return@setOnClickListener
            }
            if (VpnStateBus.loadedProfiles.isEmpty()) {
                VpnStateBus.log("Import your ZIP file first.")
                return@setOnClickListener
            }
            prefs.edit().putString("u", u).putString("p", p).apply()
            VpnStateBus.username = u
            VpnStateBus.password = p

            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                startVpnTestService()
            }
        }

        binding.btnDisconnect.setOnClickListener {
            val i = Intent(this, AutoDelayVpnService::class.java).setAction(AutoDelayVpnService.ACTION_STOP)
            startService(i)
        }
    }

    private fun startVpnTestService() {
        binding.tvLogs.text = ""
        val i = Intent(this, AutoDelayVpnService::class.java).setAction(AutoDelayVpnService.ACTION_START_TEST)
        ContextCompat.startForegroundService(this, i)
    }
}
