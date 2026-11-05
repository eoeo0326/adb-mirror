package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection
import kotlinx.coroutines.flow.Flow

/** 세션이 쓰는 View inspector 명령. 실제로는 [ViewInspector]이고, 테스트는 가짜를 끼운다. */
interface ViewInspection {
    val events: Flow<ViewLayoutInspection.Event>
    val disposals: Flow<String>

    suspend fun useBitmapScreenshots(scale: Float = 1f)
    suspend fun startFetch(continuous: Boolean): ViewLayoutInspection.StartFetchResponse
    suspend fun stopFetch()
    suspend fun properties(rootViewId: Long, viewId: Long): ViewLayoutInspection.GetPropertiesResponse
    suspend fun dispose()
}

/** 세션이 쓰는 Compose inspector 명령. 실제로는 [ComposeInspector]. */
interface ComposeInspection {
    val disposals: Flow<String>

    suspend fun updateSettings(
        includeRecomposeCounts: Boolean = true,
        keepRecomposeCounts: Boolean = false,
        delayParameterExtractions: Boolean = true,
        reduceChildNesting: Boolean = true,
    ): ComposeLayoutInspection.Response

    suspend fun composables(rootViewId: Long, skipSystem: Boolean, generation: Int, allowEmptyIfUnchanged: Boolean = true): ComposeLayoutInspection.Response
    suspend fun parameters(rootViewId: Long, composableId: Long, anchorHash: Int, skipSystem: Boolean, generation: Int): ComposeLayoutInspection.Response
    suspend fun dispose()
}
