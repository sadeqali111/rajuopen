package com.ovpn.autodelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import com.mlmvpn.scanner.openvpn.OpenVpnNative
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis

class AutoDelayVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mainJob: Job? = null
    @Volatile private var activeSession: NativeSession? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Ovpn AutoDelay", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TEST -> {
                startForeground(NOTIF_ID, buildNotification("Testing servers..."))
                mainJob?.cancel()
                mainJob = scope.launch { runAllAndConnectFastest() }
            }
            ACTION_STOP -> {
                mainJob?.cancel()
                activeSession?.stop()
                activeSession = null
                VpnStateBus.statusText.value = "Disconnected"
                VpnStateBus.log("Disconnected by user.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runAllAndConnectFastest() {
        if (!OpenVpnNative.available()) {
            VpnStateBus.statusText.value = "ERROR: libmlmopenvpn.so missing!"
            VpnStateBus.log("ERROR: libmlmopenvpn.so not found in jniLibs/arm64-v8a/")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val profiles = VpnStateBus.loadedProfiles
        val user = VpnStateBus.username
        val pass = VpnStateBus.password

        if (profiles.isEmpty()) {
            VpnStateBus.log("No profiles loaded.")
            return
        }

        VpnStateBus.log("Starting Real Delay test on ALL ${profiles.size} servers (TCP SplitRelay)...")
        val working = mutableListOf<OvpnProfileItem>()

        for ((idx, profile) in profiles.withIndex()) {
            if (!scope.isActive) return
            val label = "[${idx + 1}/${profiles.size}] ${profile.name} (${profile.host}:${profile.port})"
            VpnStateBus.statusText.value = "Testing $label"
            VpnStateBus.log("Testing $label ...")

            val delayMs = testSingleProfile(profile, user, pass)
            profile.realDelayMs = delayMs
            if (delayMs < Long.MAX_VALUE) {
                working.add(profile)
                VpnStateBus.log("  -> OK | Real Delay: ${delayMs}ms")
            } else {
                VpnStateBus.log("  -> Failed / Timeout")
            }
        }

        val fastest = working.minByOrNull { it.realDelayMs }
        if (fastest == null) {
            VpnStateBus.statusText.value = "No working server found."
            VpnStateBus.log("Finished: 0 working servers.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        VpnStateBus.log("Fastest server: ${fastest.name} (${fastest.realDelayMs}ms). Connecting...")
        VpnStateBus.statusText.value = "Connected: ${fastest.name} (${fastest.realDelayMs}ms)"
        updateNotification("Connected: ${fastest.name} (${fastest.realDelayMs}ms)")

        val finalSession = NativeSession(fastest, user, pass)
        activeSession = finalSession
        finalSession.runBlockingForever()
    }

    private suspend fun testSingleProfile(profile: OvpnProfileItem, user: String, pass: String): Long {
        val session = NativeSession(profile, user, pass)
        activeSession = session
        val job = scope.launch { session.runBlockingForever() }

        val connected = withTimeoutOrNull(25_000L) {
            while (!session.isConnected && !session.isFinished) {
                delay(200)
            }
            session.isConnected
        } ?: false

        var realDelay = Long.MAX_VALUE
        if (connected) {
            delay(1_200)
            for (attempt in 1..3) {
                realDelay = measureRealHttpDelay(6_000)
                if (realDelay < Long.MAX_VALUE) break
                if (attempt < 3) delay(1_000)
            }
        }

        session.stop()
        withTimeoutOrNull(3_000L) { job.join() }
        session.forceCleanup()
        activeSession = null
        delay(300)
        return realDelay
    }

    private fun measureRealHttpDelay(timeoutMs: Int): Long {
        return try {
            var code = 0
            val elapsed = measureTimeMillis {
                val conn = URL("http://www.gstatic.com/generate_204").openConnection() as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.useCaches = false
                conn.setRequestProperty("Connection", "close")
                code = conn.responseCode
                conn.disconnect()
            }
            if (code == 204 || code == 200) elapsed else Long.MAX_VALUE
        } catch (e: Exception) {
            VpnStateBus.log("  -> HTTP ERR: ${e.javaClass.simpleName}: ${e.message}")
            Long.MAX_VALUE
        }
    }

    @Keep
    private inner class NativeSession(
        private val profile: OvpnProfileItem,
        private val username: String,
        private val password: String
    ) {
        private val stopped = AtomicBoolean(false)
        private val handle = AtomicLong(0L)
        private var builder: Builder? = null
        private var retainedTun: ParcelFileDescriptor? = null
        private var relay: OpenVpnSplitRelay? = null

        @Volatile var isConnected = false
        @Volatile var isFinished = false

        fun runBlockingForever() {
            try {
                val r = OpenVpnSplitRelay(this@AutoDelayVpnService, listOf(profile.host), profile.port)
                relay = r
                r.start()
                val cfg = OvpnConfigParser.rewriteForLocalRelay(profile.rawConfig, r.localPort)
                val evalErr = OpenVpnNative.evaluate(cfg)
                if (evalErr.isNotEmpty()) {
                    VpnStateBus.log("  -> EVAL_ERR: $evalErr")
                    return
                }
                val id = OpenVpnNative.create()
                if (id == 0L) {
                    VpnStateBus.log("  -> CREATE_FAILED (id=0)")
                    return
                }
                handle.set(id)
                if (stopped.get()) {
                    OpenVpnNative.stop(id)
                    return
                }
                val runErr = OpenVpnNative.run(id, cfg, username, password, this)
                if (runErr.isNotEmpty()) {
                    VpnStateBus.log("  -> RUN_ERR: $runErr")
                }
            } catch (e: Exception) {
                VpnStateBus.log("  -> EXCEPTION: ${e.message}")
            } finally {
                isFinished = true
                forceCleanup()
            }
        }

        fun stop() {
            stopped.set(true)
            handle.get().takeIf { it != 0L }?.let { runCatching { OpenVpnNative.stop(it) } }
        }

        fun forceCleanup() {
            stopped.set(true)
            relay?.let { relay = null; runCatching { it.close() } }
            synchronized(this) {
                runCatching { retainedTun?.close() }
                retainedTun = null
            }
            handle.getAndSet(0L).takeIf { it != 0L }?.let { runCatching { OpenVpnNative.release(it) } }
        }

        @Keep fun configureTun(operation: String, value: Int): Boolean = runCatching {
            when (operation) {
                "new" -> { builder = Builder().setSession("OvpnAutoDelay").setBlocking(false); true }
                "mtu" -> { builder?.setMtu(value.coerceIn(576, 9000)); true }
                "protect" -> protect(value)
                else -> false
            }
        }.getOrDefault(false)

        @Keep fun addAddress(address: String, prefix: Int): Boolean = runCatching {
            builder?.addAddress(address, prefix); true
        }.getOrDefault(false)

        @Keep fun addRoute(address: String, prefix: Int): Boolean = runCatching {
            builder?.addRoute(address, prefix); true
        }.getOrDefault(false)

        @Keep fun addDns(address: String, ignored: Int): Boolean = runCatching {
            builder?.addDnsServer(address); true
        }.getOrDefault(false)

        @Keep fun addSearchDomain(domain: String, ignored: Int): Boolean = runCatching {
            builder?.addSearchDomain(domain); true
        }.getOrDefault(false)

        @Keep fun establishTun(): Int {
            if (stopped.get()) return -1
            val tun = builder?.establish() ?: return -1
            retainedTun?.close()
            retainedTun = tun
            return ParcelFileDescriptor.dup(tun.fileDescriptor).detachFd()
        }

        @Keep fun onCoreEvent(name: String, error: Boolean) {
            VpnStateBus.log("  -> EVENT: $name (err=$error)")
            if (name == "CONNECTED") isConnected = true
            if (name == "AUTH_FAILED") {
                stop()
            }
        }

        @Keep fun onCoreTraffic(bytesOut: Long, bytesIn: Long) {}
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Ovpn AutoDelay")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        mainJob?.cancel()
        activeSession?.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_TEST = "com.ovpn.autodelay.START"
        const val ACTION_STOP = "com.ovpn.autodelay.STOP"
        private const val CHANNEL_ID = "ovpn_autodelay"
        private const val NOTIF_ID = 701
    }
}
