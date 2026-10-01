@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.eoeo0326.adbmirror.core.adb.web

import io.github.eoeo0326.adbmirror.core.adb.EndOfStreamException
import io.github.eoeo0326.adbmirror.core.adb.protocol.AdbChannel
import kotlinx.coroutines.await
import kotlin.js.Promise

/** 브라우저의 WebUSB 기기(`USBDevice`). 필요한 것만 선언한다. */
external interface UsbDevice : JsAny {
    val serialNumber: String?
    val productName: String?
    val opened: Boolean
}

/** 기기 안 ADB 인터페이스(클래스 0xFF, 서브클래스 0x42, 프로토콜 1)와 bulk 엔드포인트. */
private external interface AdbInterface : JsAny {
    val configuration: Int
    val interfaceNumber: Int
    val inEndpoint: Int
    val outEndpoint: Int
    val packetSize: Int
}

@JsFun("() => typeof navigator !== 'undefined' && !!navigator.usb")
external fun webUsbSupported(): Boolean

/** ADB 인터페이스만 보이는 기기 선택 창을 띄운다. 사용자가 고르지 않으면 실패한다(사용자 동작 안에서 불러야 함). */
@JsFun("() => navigator.usb.requestDevice({ filters: [{ classCode: 0xff, subclassCode: 0x42, protocolCode: 0x01 }] })")
external fun requestAdbDevice(): Promise<UsbDevice>

/** 전에 허용한 기기들(다시 고르지 않고 열 수 있음). */
@JsFun("() => navigator.usb.getDevices()")
private external fun permittedDevices(): Promise<JsArray<UsbDevice>>

@JsFun(
    """(d) => {
  for (const c of d.configurations) for (const i of c.interfaces) for (const a of i.alternates) {
    if (a.interfaceClass === 0xff && a.interfaceSubclass === 0x42 && a.interfaceProtocol === 1) {
      const inE = a.endpoints.find(e => e.direction === 'in' && e.type === 'bulk');
      const outE = a.endpoints.find(e => e.direction === 'out' && e.type === 'bulk');
      if (inE && outE) return { configuration: c.configurationValue, interfaceNumber: i.interfaceNumber,
        inEndpoint: inE.endpointNumber, outEndpoint: outE.endpointNumber, packetSize: outE.packetSize };
    }
  }
  return null;
}""",
)
private external fun findAdbInterface(device: UsbDevice): AdbInterface?

@JsFun(
    """async (d, configuration, iface) => {
  if (!d.opened) await d.open();
  if (!d.configuration || d.configuration.configurationValue !== configuration) await d.selectConfiguration(configuration);
  await d.claimInterface(iface);
}""",
)
private external fun openAndClaim(device: UsbDevice, configuration: Int, interfaceNumber: Int): Promise<JsAny?>

@JsFun("async (d, iface) => { try { await d.releaseInterface(iface); } catch (e) {} try { await d.close(); } catch (e) {} }")
private external fun releaseAndClose(device: UsbDevice, interfaceNumber: Int): Promise<JsAny?>

/** 받은 데이터를 JS 쪽에 붙잡아 두고 길이를 돌려준다(바이트는 [takeByte]로 꺼냄). 기기가 끊기면 -1. */
@JsFun(
    """async (d, ep, length) => {
  const r = await d.transferIn(ep, length);
  if (r.status !== 'ok' || !r.data) return -1;
  globalThis.__adbMirrorIn = new Uint8Array(r.data.buffer, r.data.byteOffset, r.data.byteLength);
  return r.data.byteLength;
}""",
)
private external fun transferIn(device: UsbDevice, endpoint: Int, length: Int): Promise<JsNumber>

@JsFun("(i) => globalThis.__adbMirrorIn[i]")
private external fun takeByte(index: Int): Int

@JsFun("(n) => { globalThis.__adbMirrorOut = new Uint8Array(n); }")
private external fun beginOut(length: Int)

@JsFun("(i, v) => { globalThis.__adbMirrorOut[i] = v; }")
private external fun putByte(index: Int, value: Int)

@JsFun("(d, ep) => d.transferOut(ep, globalThis.__adbMirrorOut).then(r => r.status === 'ok')")
private external fun transferOut(device: UsbDevice, endpoint: Int): Promise<JsBoolean>

@JsFun("(d, ep) => d.transferOut(ep, new Uint8Array(0)).then(r => r.status === 'ok')")
private external fun transferZeroLength(device: UsbDevice, endpoint: Int): Promise<JsBoolean>

/**
 * WebUSB bulk 엔드포인트 위의 [AdbChannel]. adb는 헤더와 payload를 따로 보내므로 요청한 만큼만 받는다.
 * 보낼 길이가 패킷 크기의 배수면 끝을 알리려고 길이 0 전송을 덧붙인다(adb 호스트와 같음).
 * 바이트는 JS 배열과 한 개씩 주고받는다(Kotlin/Wasm과 JS가 메모리를 공유하지 않음).
 */
class WebUsbAdbChannel private constructor(private val device: UsbDevice, private val iface: AdbInterface) : AdbChannel {
    private var buffer = ByteArray(0)

    val serial: String = device.serialNumber?.takeIf { it.isNotBlank() } ?: "usb-${device.productName ?: "device"}"
    val productName: String? = device.productName

    override suspend fun readFully(count: Int): ByteArray {
        while (buffer.size < count) {
            val n = transferIn(device, iface.inEndpoint, count - buffer.size).await<JsNumber>().toInt()
            if (n < 0) throw EndOfStreamException("USB 연결이 끊겼습니다")
            buffer += ByteArray(n) { takeByte(it).toByte() }
        }
        return buffer.copyOf(count).also { buffer = buffer.copyOfRange(count, buffer.size) }
    }

    override suspend fun write(bytes: ByteArray) {
        beginOut(bytes.size)
        for (i in bytes.indices) putByte(i, bytes[i].toInt() and 0xFF)
        if (!transferOut(device, iface.outEndpoint).await<JsBoolean>().toBoolean()) throw EndOfStreamException("USB로 보내지 못했습니다")
        if (bytes.isNotEmpty() && bytes.size % iface.packetSize == 0) transferZeroLength(device, iface.outEndpoint).await<JsBoolean>()
    }

    override suspend fun close() {
        releaseAndClose(device, iface.interfaceNumber).await<JsAny?>()
    }

    companion object {
        /**
         * 기기를 열고 ADB 인터페이스를 차지한다. 이 컴퓨터에서 adb 서버가 돌고 있으면 그쪽이 인터페이스를 쥐고 있어
         * 실패하므로(`adb kill-server`가 필요) 그 안내를 담아 던진다.
         */
        suspend fun open(device: UsbDevice): WebUsbAdbChannel {
            val iface = findAdbInterface(device) ?: error("이 기기에서 ADB 인터페이스를 찾지 못했습니다. USB 디버깅이 켜져 있는지 확인하세요")
            try {
                openAndClaim(device, iface.configuration, iface.interfaceNumber).await<JsAny?>()
            } catch (e: Throwable) {
                throw IllegalStateException("기기를 열지 못했습니다. 이 컴퓨터에서 adb가 돌고 있다면 `adb kill-server`로 끄고 다시 시도하세요 (${e.message})", e)
            }
            return WebUsbAdbChannel(device, iface)
        }

        /** 전에 허용한 기기들. */
        suspend fun permitted(): List<UsbDevice> {
            val array = permittedDevices().await<JsArray<UsbDevice>>()
            return (0 until array.length).mapNotNull { array[it] }
        }
    }
}
