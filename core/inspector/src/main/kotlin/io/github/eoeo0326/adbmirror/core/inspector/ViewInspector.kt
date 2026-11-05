package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.InspectorFailure
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.CreateInspectorResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.EnableBitmapScreenshotCommand
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.GetPropertiesCommand
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.GetPropertiesResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.Screenshot
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.StartFetchCommand
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.StartFetchResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.StopFetchCommand
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.UpdateScreenshotTypeCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/** Android Studio 번들 View inspector(`layoutinspector.view.inspection`) 프로토콜 래퍼. */
class ViewInspector(private val client: AppInspectionClient) {
    /** inspector 이벤트. 폐기되면 [InspectorRawEvent.Disposed]가 [disposals]로 온다. */
    val events: Flow<ViewLayoutInspection.Event> = client.events.mapNotNull { e ->
        (e as? InspectorRawEvent.Raw)?.takeIf { it.inspectorId == ID }?.let { ViewLayoutInspection.Event.parseFrom(it.bytes) }
    }

    val disposals: Flow<String> = client.events.mapNotNull { e -> (e as? InspectorRawEvent.Disposed)?.takeIf { it.inspectorId == ID }?.errorMessage }

    suspend fun create(dexPath: String = DeviceFiles.VIEW_INSPECTOR_JAR) {
        val status = client.createInspector(ID, dexPath)
        if (status != CreateInspectorResponse.Status.SUCCESS) {
            throw InspectorException(InspectorFailure.InspectorFailed, "View inspector를 만들지 못했습니다: $status")
        }
    }

    suspend fun startFetch(continuous: Boolean): StartFetchResponse =
        command { setStartFetchCommand(StartFetchCommand.newBuilder().setContinuous(continuous)) }.startFetchResponse

    suspend fun stopFetch() {
        command { setStopFetchCommand(StopFetchCommand.getDefaultInstance()) }
    }

    suspend fun properties(rootViewId: Long, viewId: Long): GetPropertiesResponse =
        command { setGetPropertiesCommand(GetPropertiesCommand.newBuilder().setRootViewId(rootViewId).setViewId(viewId)) }.getPropertiesResponse

    suspend fun useBitmapScreenshots(scale: Float = 1f) {
        command { setUpdateScreenshotTypeCommand(UpdateScreenshotTypeCommand.newBuilder().setType(Screenshot.Type.BITMAP).setScale(scale)) }
        command { setEnableBitmapScreenshotCommand(EnableBitmapScreenshotCommand.newBuilder().setEnable(true)) }
    }

    suspend fun dispose() = client.disposeInspector(ID)

    private suspend fun command(fill: ViewLayoutInspection.Command.Builder.() -> Unit): ViewLayoutInspection.Response {
        val bytes = client.raw(ID, ViewLayoutInspection.Command.newBuilder().apply(fill).build().toByteArray())
        return ViewLayoutInspection.Response.parseFrom(bytes)
    }

    companion object {
        const val ID = "layoutinspector.view.inspection"
    }
}
