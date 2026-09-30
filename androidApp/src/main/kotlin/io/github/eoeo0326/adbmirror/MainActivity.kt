package io.github.eoeo0326.adbmirror

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
    LaunchedEffect(listViewModel) {
        listViewModel.effects.collect { effect ->
            when (effect) {
                is DeviceListEffect.OpenMirror -> if (mirror?.device?.serial != effect.device.serial) {
                    mirror?.let { old -> scope.launch { old.close() } }
                    mirror = MirrorHolder(effect.device, graph)
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
