package io.github.eoeo0326.adbmirror.feature.conversion

/**
 * 저장한 파일을 OS에서 연다. 플랫폼마다 구현해 넘기고, 열 수 없는 플랫폼(Web 다운로드)은 넘기지 않는다.
 * [uri]는 [ConversionState.Done.uri]다(Android content Uri). 실패는 구현이 알아서 알린다.
 */
interface FileOpener {
    fun canOpen(file: String, uri: String?): Boolean
    /** 기본 앱으로 연다. */
    fun open(file: String, uri: String?)
    fun canReveal(file: String): Boolean
    /** 파일이 든 폴더를 연다(가능하면 그 파일을 선택한 채로). */
    fun reveal(file: String)
}
