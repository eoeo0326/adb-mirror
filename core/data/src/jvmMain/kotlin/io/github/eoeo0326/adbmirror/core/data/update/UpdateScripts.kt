package io.github.eoeo0326.adbmirror.core.data.update

import java.io.File

/**
 * 앱이 끝난 뒤에 돌아 설치본을 바꾸고 앱을 다시 여는 스크립트. 앱이 자기 파일을 쓰는 동안에는 바꿀 수 없어서
 * 설치는 별도 프로세스가 한다. 모든 스크립트는 먼저 [pid] 프로세스(지금 앱)가 끝나기를 기다린다.
 */
internal object UpdateScripts {
    /** macOS: dmg를 붙여 새 `.app`을 옆에 복사한 뒤 바꿔치기한다. 실패하면 원래 번들을 되돌린다. */
    fun mac(pid: Long, dmg: File, bundle: File): String = """
        |#!/bin/bash
        |# ADB Mirror 업데이트: 앱이 끝나면 번들을 바꾸고 다시 연다.
        |pid=$pid
        |dmg=${sh(dmg.path)}
        |app=${sh(bundle.path)}
        |while kill -0 "${'$'}pid" 2>/dev/null; do sleep 0.5; done
        |mnt=${'$'}(mktemp -d /tmp/adb-mirror-update.XXXXXX)
        |if hdiutil attach -nobrowse -readonly -mountpoint "${'$'}mnt" "${'$'}dmg" >/dev/null; then
        |  src=${'$'}(find "${'$'}mnt" -maxdepth 1 -name '*.app' | head -n 1)
        |  rm -rf "${'$'}app.new"
        |  if [ -n "${'$'}src" ] && ditto "${'$'}src" "${'$'}app.new"; then
        |    rm -rf "${'$'}app.old"
        |    if mv "${'$'}app" "${'$'}app.old"; then
        |      if mv "${'$'}app.new" "${'$'}app"; then rm -rf "${'$'}app.old"; else mv "${'$'}app.old" "${'$'}app"; fi
        |    fi
        |    xattr -dr com.apple.quarantine "${'$'}app" 2>/dev/null
        |  fi
        |  rm -rf "${'$'}app.new"
        |  hdiutil detach -quiet "${'$'}mnt"
        |fi
        |rmdir "${'$'}mnt" 2>/dev/null
        |open "${'$'}app"
        |""".trimMargin()

    /** Windows MSI: 사용자 설치(perUserInstall)라 관리자 권한 없이 같은 자리에 올려 설치된다. */
    fun windowsMsi(pid: Long, msi: File, exe: File): String = """
        |# ADB Mirror 업데이트: 앱이 끝나면 MSI로 설치하고 다시 연다.
        |Wait-Process -Id $pid -ErrorAction SilentlyContinue
        |Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/i', ${ps("\"${msi.path}\"")}, '/passive') -Wait
        |if (Test-Path -LiteralPath ${ps(exe.path)}) { Start-Process -FilePath ${ps(exe.path)} }
        |""".trimMargin()

    /** Windows 포터블: zip의 `ADB Mirror/`를 그 자리에 덮어쓴다. 설정이 든 `data/`는 건드리지 않는다. */
    fun windowsPortable(pid: Long, zip: File, root: File, exe: File): String = """
        |# ADB Mirror 업데이트: 앱이 끝나면 포터블 폴더를 덮어쓰고 다시 연다.
        |Wait-Process -Id $pid -ErrorAction SilentlyContinue
        |${'$'}tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('adb-mirror-update-' + [guid]::NewGuid())
        |Expand-Archive -LiteralPath ${ps(zip.path)} -DestinationPath ${'$'}tmp -Force
        |robocopy (Join-Path ${'$'}tmp 'ADB Mirror') ${ps(root.path)} /E /XD data /NFL /NDL /NJH /NJS | Out-Null
        |Remove-Item -LiteralPath ${'$'}tmp -Recurse -Force -ErrorAction SilentlyContinue
        |Start-Process -FilePath ${ps(exe.path)}
        |""".trimMargin()

    /** Linux 포터블: tar.gz의 `ADB Mirror/`를 그 자리에 덮어쓴다. 압축 파일에 `data/`가 없어 설정은 남는다. */
    fun linuxPortable(pid: Long, archive: File, root: File, exe: File): String = """
        |#!/bin/bash
        |# ADB Mirror 업데이트: 앱이 끝나면 포터블 폴더를 덮어쓰고 다시 연다.
        |pid=$pid
        |archive=${sh(archive.path)}
        |root=${sh(root.path)}
        |exe=${sh(exe.path)}
        |while kill -0 "${'$'}pid" 2>/dev/null; do sleep 0.5; done
        |tmp=${'$'}(mktemp -d /tmp/adb-mirror-update.XXXXXX)
        |if tar -xzf "${'$'}archive" -C "${'$'}tmp" && [ -d "${'$'}tmp/ADB Mirror" ]; then
        |  cp -a "${'$'}tmp/ADB Mirror/." "${'$'}root/"
        |fi
        |rm -rf "${'$'}tmp"
        |nohup "${'$'}exe" >/dev/null 2>&1 &
        |""".trimMargin()

    /** bash 작은따옴표 문자열. 안의 작은따옴표는 `'\''`로 바꾼다. */
    fun sh(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** PowerShell 작은따옴표 문자열. 안의 작은따옴표는 두 번 쓴다. */
    fun ps(value: String): String = "'" + value.replace("'", "''") + "'"
}
