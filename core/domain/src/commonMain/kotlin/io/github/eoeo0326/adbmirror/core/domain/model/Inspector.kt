package io.github.eoeo0326.adbmirror.core.domain.model

import kotlinx.coroutines.flow.Flow

/** Layout Inspector로 들여다볼 수 있는 debuggable 프로세스. */
data class InspectableProcess(
    val pid: Int,
    val packageName: String,
    /** 프로세스 이름. 보통 패키지명과 같고, 별도 프로세스면 `pkg:remote`처럼 붙는다. */
    val name: String,
    /** 에이전트 파일을 고르는 CPU 아키텍처(`arm64`, `arm`, `x86`, `x86_64`). */
    val abiCpuArch: String,
    val foreground: Boolean = false,
)

/** 기기 디스플레이 좌표의 사각형(px). */
data class NodeBounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun contains(px: Int, py: Int): Boolean = px >= x && py >= y && px < x + width && py < y + height
}

/** View 또는 Composable 하나. View 트리 안에 Compose 트리가 붙은 한 그루로 다룬다. */
data class LayoutNode(
    /** View는 view id, Composable은 composable id. */
    val id: Long,
    val kind: Kind,
    /** View는 클래스 단순 이름, Composable은 함수 이름. */
    val name: String,
    /** View의 패키지+클래스. Composable은 null. */
    val qualifiedName: String?,
    val bounds: NodeBounds,
    val children: List<LayoutNode>,
    /** 이 노드가 속한 창(root view)의 id. */
    val rootId: Long,
    val textValue: String? = null,
    val sourceFile: String? = null,
    val sourceLine: Int? = null,
    /** Compose 노드만 값이 있다. 앱의 Compose가 횟수를 지원하지 않으면 null. */
    val recomposeCount: Int? = null,
    val recomposeSkips: Int? = null,
    /** Compose 파라미터를 조회할 때 함께 보내는 값. */
    val anchorHash: Int? = null,
    /** Compose 라이브러리가 만든 노드(시스템 컴포저블). */
    val systemCreated: Boolean = false,
) {
    enum class Kind { View, Composable }
}

/** 속성 패널 한 줄. [group]으로 묶어 보이고, 중첩 값은 [children]으로 펼친다. */
data class InspectorProperty(
    val group: String,
    val name: String,
    val value: String,
    val children: List<InspectorProperty> = emptyList(),
)

/**
 * 창 하나의 화면 이미지(ARGB, 행 우선). 크기가 커서 MVI State에 넣지 않는다(영상 프레임과 같은 규칙).
 * 같은 내용이어도 매번 새 객체라 equals를 정의하지 않는다.
 */
class InspectorScreenshot(val rootId: Long, val width: Int, val height: Int, val argb: IntArray)

/** 기기를 준비하는 단계. 상태줄에 보인다. */
sealed interface InspectorStep {
    data object LocatingStudio : InspectorStep
    data object PushingFiles : InspectorStep
    data object StartingDaemon : InspectorStep
    data object Attaching : InspectorStep
    data object LoadingInspectors : InspectorStep
    data class DownloadingCompose(val version: String) : InspectorStep
}

enum class InspectorFailure {
    /** 설치된 Android Studio를 찾지 못했거나 필요한 파일이 없다. */
    StudioNotFound,

    /** 기기 API 29 미만. */
    ApiTooLow,

    /** 에이전트를 붙이지 못했다(UNATTACHABLE·시간 초과). */
    AttachFailed,

    /** View inspector를 만들지 못했거나 트리를 받지 못했다. */
    InspectorFailed,

    /** 검사하던 프로세스가 끝났다. */
    ProcessDied,

    /** transport 연결이 끊겼다. */
    Disconnected,
}

/** Layout Inspector 준비·연결 실패. [failure]로 화면 문구를 고른다. */
class InspectorException(val failure: InspectorFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface InspectorEvent {
    data class Progress(val step: InspectorStep) : InspectorEvent

    /** 창(root)들의 트리 전체. 여러 창이면 z 순서(아래→위)대로 담는다. */
    data class Tree(val roots: List<LayoutNode>, val composeAvailable: Boolean) : InspectorEvent

    data class Screenshot(val shot: InspectorScreenshot) : InspectorEvent

    /** Compose 정보를 볼 수 없다. View 트리는 계속 온다. */
    data class ComposeUnavailable(val reason: String) : InspectorEvent

    /** inspector가 보낸 오류 메시지. 세션은 계속된다. */
    data class DeviceError(val message: String) : InspectorEvent

    /** 세션이 더 진행할 수 없다. */
    data class Failed(val reason: InspectorFailure, val detail: String? = null) : InspectorEvent
}

/** 기기 하나에 준비된 Layout Inspector 연결. 프로세스를 골라 [inspect]한다. */
interface InspectorConnection {
    val serial: String

    /** 쓰고 있는 Android Studio 이름(예: `Android Studio 2026.2.1`). 상태줄에 보인다. */
    val studioLabel: String

    /** 검사할 수 있는(debuggable) 프로세스. 포그라운드 프로세스가 앞에 온다. */
    suspend fun processes(): List<InspectableProcess>

    /** 프로세스에 붙어 세션을 연다. 실패하면 [InspectorFailure]를 담은 예외. 한 번에 세션 하나만 연다. */
    suspend fun inspect(process: InspectableProcess, onStep: (InspectorStep) -> Unit = {}): InspectorSession

    /** 열린 세션을 닫고 transport 연결·forward·(띄운) 데몬·기기 설정을 정리한다. 여러 번 불러도 된다. */
    suspend fun close()
}

/** 프로세스 하나를 들여다보는 세션. 트리·스크린샷·오류는 [events]로 온다. */
interface InspectorSession {
    val process: InspectableProcess
    val events: Flow<InspectorEvent>

    /** 앱의 Compose UI 버전. Compose 정보를 볼 수 없으면 null. */
    val composeVersion: String?

    suspend fun setLive(live: Boolean)

    /** 실시간이 꺼져 있을 때 한 번 다시 받는다. */
    suspend fun refresh()

    suspend fun resetRecompositionCounts()

    suspend fun setShowSystemComposables(show: Boolean)

    /** View는 속성, Composable은 파라미터·semantics. */
    suspend fun properties(node: LayoutNode): List<InspectorProperty>

    /** inspector를 폐기한다. 여러 번 불러도 된다. 연결은 [InspectorConnection.close]가 닫는다. */
    suspend fun close()
}
