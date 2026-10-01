package io.github.eoeo0326.adbmirror

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import io.github.eoeo0326.adbmirror.android.R
import io.github.eoeo0326.adbmirror.core.domain.model.WirelessPairing
import io.github.eoeo0326.adbmirror.core.domain.model.WirelessService
import io.github.eoeo0326.adbmirror.core.domain.repository.WirelessDiscoveryRepository
import io.github.eoeo0326.adbmirror.core.domain.usecase.ConnectWirelessDeviceUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.PairDeviceUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 이 폰 자신을 페어링할 때 쓴다. 이 앱으로 돌아오면 설정 앱의 페어링 창이 닫혀 포트가 사라지므로,
 * 코드를 알림 답장(RemoteInput)으로 받고 페어링 서비스는 mDNS로 찾는다. 페어링하면 같은 주소의 연결 서비스로 연결까지 한다.
 */
class NotificationPairing(
    private val context: Context,
    private val scope: CoroutineScope,
    private val discovery: WirelessDiscoveryRepository,
    private val pair: PairDeviceUseCase,
    private val connect: ConnectWirelessDeviceUseCase,
) {
    private val services = MutableStateFlow<List<WirelessService>>(emptyList())
    private val lock = Mutex()
    private var watch: Job? = null

    /** 알림을 띄우고 페어링 서비스를 찾기 시작한다. [WATCH_MS] 동안 코드가 오지 않으면 알림을 거둔다. */
    fun start() {
        createChannel()
        watch?.cancel()
        watch = scope.launch {
            val collect = launch { discovery.services().collect { services.value = it } }
            delay(WATCH_MS)
            collect.cancel()
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
        notify(askForCode("설정 > 개발자 옵션 > 무선 디버깅 > \"페어링 코드로 기기 페어링\"을 연 뒤, 나온 6자리 코드를 답장으로 입력하세요."))
    }

    /** 알림 답장으로 받은 코드. 하나씩 처리한다. */
    suspend fun submit(code: String) = lock.withLock {
        notify(progress("페어링하는 중…"))
        val target = withTimeoutOrNull(FIND_MS) {
            services.mapNotNull { WirelessPairing.choose(it, localHosts()) }.first()
        }
        if (target == null) {
            notify(askForCode("페어링 창을 찾지 못했습니다. 이 폰과 같은 Wi-Fi에서 페어링 창을 연 채로 다시 입력하세요."))
            return@withLock
        }
        try {
            pair(target.host, target.port.toString(), code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notify(askForCode("페어링하지 못했습니다: ${e.message ?: e::class.simpleName}. 코드를 확인하고 다시 입력하세요."))
            return@withLock
        }
        notify(progress("페어링했습니다. 연결하는 중…"))
        val connectService = withTimeoutOrNull(FIND_MS) {
            services.mapNotNull { list -> list.firstOrNull { it.kind == WirelessService.Kind.Connect && it.host == target.host } }.first()
        }
        val message = if (connectService == null) {
            "페어링했습니다. 앱에서 연결 포트로 연결하세요."
        } else {
            try {
                val device = connect(connectService.host, connectService.port.toString())
                "${device.model ?: device.serial}에 연결했습니다."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "페어링했지만 연결하지 못했습니다: ${e.message ?: e::class.simpleName}"
            }
        }
        watch?.cancel()
        notify(done(message))
    }

    private fun base(text: String) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("ADB Mirror 페어링")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setOnlyAlertOnce(true)

    private fun askForCode(text: String) = base(text)
        .setOngoing(true)
        .addAction(
            NotificationCompat.Action.Builder(R.drawable.ic_notification, "코드 입력", replyIntent())
                .addRemoteInput(RemoteInput.Builder(KEY_CODE).setLabel("페어링 코드 6자리").build())
                .build(),
        )
        .build()

    private fun progress(text: String) = base(text).setOngoing(true).setProgress(0, 0, true).build()

    private fun done(text: String) = base(text).setAutoCancel(true).setTimeoutAfter(DONE_SHOWN_MS).build()

    private fun replyIntent(): PendingIntent {
        // RemoteInput이 답장을 Intent에 채워 넣으므로 mutable이어야 한다.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, Intent(context, PairingCodeReceiver::class.java), flags)
    }

    @android.annotation.SuppressLint("MissingPermission") // 권한은 MainActivity가 받은 뒤 start()를 부른다. 없으면 시스템이 무시한다.
    private fun notify(notification: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "무선 디버깅 페어링", NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** 이 폰의 IPv4 주소들. 같은 네트워크의 다른 폰이 페어링 창을 열어 두어도 이 폰 것을 고른다. */
    private fun localHosts(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())

    companion object {
        const val KEY_CODE = "code"
        private const val CHANNEL_ID = "pairing"
        private const val NOTIFICATION_ID = 1
        private const val WATCH_MS = 10 * 60 * 1000L
        private const val FIND_MS = 10_000L
        private const val DONE_SHOWN_MS = 15_000L
    }
}

/** 알림 답장으로 온 페어링 코드를 [NotificationPairing]에 넘긴다. 처리가 끝날 때까지 프로세스를 붙잡아 둔다. */
class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(NotificationPairing.KEY_CODE)?.toString()?.trim() ?: return
        val app = context.applicationContext as AdbMirrorApplication
        val pending = goAsync()
        app.graph.scope.launch {
            try {
                withTimeoutOrNull(RECEIVER_LIMIT_MS) { app.graph.notificationPairing.submit(code) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /** 백그라운드 브로드캐스트 제한(60초)보다 짧게. */
        const val RECEIVER_LIMIT_MS = 50_000L
    }
}
