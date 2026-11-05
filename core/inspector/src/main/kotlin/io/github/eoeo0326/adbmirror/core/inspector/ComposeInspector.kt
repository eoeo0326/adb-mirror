package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.CreateInspectorResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.LibraryCompatibility
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection.GetComposablesCommand
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection.GetParameterDetailsCommand
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection.GetParametersCommand
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection.UpdateSettingsCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * 앱의 Compose UI 버전에 맞춰 내려받은 Compose inspector(`layoutinspector.compose.inspection`) 프로토콜 래퍼.
 * 오래된 inspector는 모르는 명령에 `unknown_command_response`로 답한다. 호출 쪽이 [ComposeLayoutInspection.Response.hasUnknownCommandResponse]로 확인한다.
 */
class ComposeInspector(private val client: AppInspectionClient) : ComposeInspection {
    override val disposals: Flow<String> = client.events.mapNotNull { e -> (e as? InspectorRawEvent.Disposed)?.takeIf { it.inspectorId == ID }?.errorMessage }

    /** 만들지 못하면 상태를 돌려준다(`VERSION_INCOMPATIBLE`·`LIBRARY_MISSING`·`APP_PROGUARDED` …). */
    suspend fun create(dexPath: String, minLibrary: LibraryCompatibility): CreateInspectorResponse.Status =
        client.createInspector(ID, dexPath, minLibrary)

    override suspend fun updateSettings(
        includeRecomposeCounts: Boolean,
        keepRecomposeCounts: Boolean,
        delayParameterExtractions: Boolean,
        reduceChildNesting: Boolean,
    ): ComposeLayoutInspection.Response = command {
        setUpdateSettingsCommand(
            UpdateSettingsCommand.newBuilder()
                .setIncludeRecomposeCounts(includeRecomposeCounts)
                .setKeepRecomposeCounts(keepRecomposeCounts)
                .setDelayParameterExtractions(delayParameterExtractions)
                .setReduceChildNesting(reduceChildNesting),
        )
    }

    override suspend fun composables(rootViewId: Long, skipSystem: Boolean, generation: Int, allowEmptyIfUnchanged: Boolean): ComposeLayoutInspection.Response =
        command {
            setGetComposablesCommand(
                GetComposablesCommand.newBuilder().setRootViewId(rootViewId).setSkipSystemComposables(skipSystem)
                    .setGeneration(generation).setExtractAllParameters(false).setAllowEmptyIfUnchanged(allowEmptyIfUnchanged),
            )
        }

    override suspend fun parameters(rootViewId: Long, composableId: Long, anchorHash: Int, skipSystem: Boolean, generation: Int): ComposeLayoutInspection.Response =
        command {
            setGetParametersCommand(
                GetParametersCommand.newBuilder().setRootViewId(rootViewId).setComposableId(composableId).setAnchorHash(anchorHash)
                    .setSkipSystemComposables(skipSystem).setMaxRecursions(2).setMaxInitialIterableSize(5).setGeneration(generation),
            )
        }

    suspend fun parameterDetails(
        rootViewId: Long,
        reference: ComposeLayoutInspection.ParameterReference,
        startIndex: Int,
        maxElements: Int,
        skipSystem: Boolean,
        generation: Int,
    ): ComposeLayoutInspection.Response = command {
        setGetParameterDetailsCommand(
            GetParameterDetailsCommand.newBuilder().setRootViewId(rootViewId).setReference(reference).setStartIndex(startIndex)
                .setMaxElements(maxElements).setSkipSystemComposables(skipSystem).setMaxRecursions(2).setMaxInitialIterableSize(5)
                .setGeneration(generation),
        )
    }

    override suspend fun dispose() = client.disposeInspector(ID)

    private suspend fun command(fill: ComposeLayoutInspection.Command.Builder.() -> Unit): ComposeLayoutInspection.Response {
        val bytes = client.raw(ID, ComposeLayoutInspection.Command.newBuilder().apply(fill).build().toByteArray())
        return ComposeLayoutInspection.Response.parseFrom(bytes)
    }

    companion object {
        const val ID = "layoutinspector.compose.inspection"
    }
}
