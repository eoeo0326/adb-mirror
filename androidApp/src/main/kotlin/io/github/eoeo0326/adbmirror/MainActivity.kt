package io.github.eoeo0326.adbmirror

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.feature.devices.DeviceListEffect
import io.github.eoeo0326.adbmirror.feature.devices.DeviceListIntent
import io.github.eoeo0326.adbmirror.feature.devices.DeviceListRoute
import io.github.eoeo0326.adbmirror.feature.mirror.MirrorRoute
import io.github.eoeo0326.adbmirror.feature.mirror.MirrorViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val graph by lazy { (application as AdbMirrorApplication).graph }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { AndroidApp(graph) } }
    }
}

/** 기기 하나의 미러링 화면. 화면을 떠날 때 세션을 정리한 뒤 ViewModel을 지운다. */
private class MirrorHolder(val device: Device, graph: AndroidAppGraph) {
    private val store = ViewModelStore()
    val viewModel: MirrorViewModel =
        ViewModelProvider.create(store, viewModelFactory { initializer { graph.mirrorViewModel(device) } })[MirrorViewModel::class]

    suspend fun close() {
        viewModel.shutdown()
        store.clear()
    }
}

@Composable
private fun AndroidApp(graph: AndroidAppGraph) {
    val listViewModel = remember { graph.deviceListViewModel() }
    var mirror by remember { mutableStateOf<MirrorHolder?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startNotificationPairing(context, graph) else Toast.makeText(context, "알림 권한이 있어야 알림으로 페어링 코드를 받을 수 있습니다", Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(listViewModel) {
        listViewModel.effects.collect { effect ->
            when (effect) {
                is DeviceListEffect.OpenMirror -> if (mirror?.device?.serial != effect.device.serial) {
                    mirror?.let { old -> scope.launch { old.close() } }
                    mirror = MirrorHolder(effect.device, graph)
                }
                DeviceListEffect.StartNotificationPairing ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        startNotificationPairing(context, graph)
                    }
            }
        }
    }
    val current = mirror
    if (current == null) {
        DeviceListRoute(listViewModel, Modifier.fillMaxSize().safeDrawingPadding())
    } else {
        BackHandler {
            mirror = null
            listViewModel.onIntent(DeviceListIntent.MirrorClosed(current.device.serial))
            scope.launch { current.close() }
        }
        MirrorRoute(current.viewModel, Modifier.fillMaxSize().safeDrawingPadding())
    }
}

/** 페어링 코드를 받을 알림을 띄우고 개발자 옵션을 연다. 사용자는 거기서 무선 디버깅 > 페어링 창으로 간다. */
private fun startNotificationPairing(context: Context, graph: AndroidAppGraph) {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        Toast.makeText(context, "이 앱의 알림이 꺼져 있습니다. 설정에서 알림을 켜세요", Toast.LENGTH_LONG).show()
        return
    }
    graph.notificationPairing.start()
    try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "설정 > 개발자 옵션 > 무선 디버깅을 여세요", Toast.LENGTH_LONG).show()
    }
}
