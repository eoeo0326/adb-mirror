@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.eoeo0326.adbmirror.core.adb.web

import io.github.eoeo0326.adbmirror.core.adb.EndOfStreamException
import io.github.eoeo0326.adbmirror.core.adb.protocol.AdbChannel
import io.github.eoeo0326.adbmirror.core.adb.protocol.AdbConnection
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
    val alternate: Int
    val inEndpoint: Int
    val outEndpoint: Int
    val packetSize: Int
    val inPacketSize: Int
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
        alternate: a.alternateSetting, inEndpoint: inE.endpointNumber, outEndpoint: outE.endpointNumber, packetSize: outE.packetSize, inPacketSize: inE.packetSize };
    }
  }
  return null;
}""",
)
private external fun findAdbInterface(device: UsbDevice): AdbInterface?

@JsFun(
    """async (d, configuration, iface, alternate, inEp, outEp) => {
  if (!d.opened) await d.open();
  if (!d.configuration || d.configuration.configurationValue !== configuration) await d.selectConfiguration(configuration);
  await d.claimInterface(iface);
  // ADB 인터페이스는 alternate 0만 있고 halt도 풀지 않는다(libusb로 확인한 정상 흐름: 열기 → 차지 → 전송).
  if (alternate !== 0) await d.selectAlternateInterface(iface, alternate);
  console.log('[adb-mirror] USB 열림', d.productName, d.serialNumber, 'iface', iface, 'alt', alternate, 'in', inEp, 'out', outEp);
}""",
)
private external fun openAndClaim(device: UsbDevice, configuration: Int, interfaceNumber: Int, alternate: Int, inEndpoint: Int, outEndpoint: Int): Promise<JsAny?>

@JsFun("async (d, iface) => { try { await d.releaseInterface(iface); } catch (e) {} try { await d.close(); } catch (e) {} }")
private external fun releaseAndClose(device: UsbDevice, interfaceNumber: Int): Promise<JsAny?>

/**
 * 받은 데이터(`Uint8Array`). 기기가 끊기면 null. 호출마다 따로 돌려줘 동시에 불러도 섞이지 않는다.
 * 엔드포인트가 멈췄으면(stall) halt를 풀고 한 번 더 읽는다. 실패 내용은 콘솔에 남긴다.
 */
@JsFun(
    """async (d, ep, length) => {
  for (let attempt = 0; attempt < 2; attempt++) {
    let r;
    try { r = await d.transferIn(ep, length); }
    catch (e) { console.warn('[adb-mirror] transferIn', length, e); throw e; }
    if (r.status === 'stall') { console.warn('[adb-mirror] transferIn stall, clearHalt'); await d.clearHalt('in', ep); continue; }
    if (r.status !== 'ok' || !r.data) { console.warn('[adb-mirror] transferIn status', r.status); return null; }
    return new Uint8Array(r.data.buffer, r.data.byteOffset, r.data.byteLength);
  }
  return null;
}""",
)
private external fun transferIn(device: UsbDevice, endpoint: Int, length: Int): Promise<JsAny?>

@JsFun("(n) => new Uint8Array(n)")
internal external fun newBytes(length: Int): JsAny

@JsFun("(a) => a.length")
internal external fun bytesLength(array: JsAny): Int

@JsFun("(a, i) => a[i]")
internal external fun byteAt(array: JsAny, index: Int): Int

@JsFun("(a, i, v) => { a[i] = v; }")
internal external fun setByteAt(array: JsAny, index: Int, value: Int)

/** JS `Uint8Array` → [ByteArray]. Kotlin/Wasm과 JS는 메모리를 공유하지 않아 한 바이트씩 옮긴다. */
internal fun JsAny.toByteArray(): ByteArray = ByteArray(bytesLength(this)) { byteAt(this, it).toByte() }

internal fun ByteArray.toUint8Array(): JsAny = newBytes(size).also { a -> forEachIndexed { i, b -> setByteAt(a, i, b.toInt() and 0xFF) } }

@JsFun("(d, ep, data) => d.transferOut(ep, data).then(r => { if (r.status !== 'ok') console.warn('[adb-mirror] transferOut status', r.status); return r.status === 'ok'; }, e => { console.warn('[adb-mirror] transferOut', data.length, e); throw e; })")
private external fun transferOut(device: UsbDevice, endpoint: Int, data: JsAny): Promise<JsBoolean>

/**
 * WebUSB bulk 엔드포인트 위의 [AdbChannel]. 읽기는 늘 IN 패킷 크기의 배수로 요청하고 남은 바이트는 다음 읽기에 쓴다.
 * 필요한 만큼(예: 헤더 24바이트)만 요청하면 기기가 보낸 패킷이 그보다 클 때 overflow 오류가 난다.
 * 보낼 길이가 패킷 크기의 배수면 끝을 알리려고 길이 0 전송을 덧붙인다(adb 호스트와 같음).
 * 읽기는 [AdbConnection]의 읽기 루프 하나, 쓰기는 그 연결의 쓰기 락 아래에서만 부른다.
 */
class WebUsbAdbChannel private constructor(private val device: UsbDevice, private val iface: AdbInterface) : AdbChannel {
    private var buffer = ByteArray(0)

    val serial: String = serialOf(device)
    val productName: String? = device.productName

    override suspend fun readFully(count: Int): ByteArray {
        while (buffer.size < count) {
            val needed = count - buffer.size
            val packet = iface.inPacketSize.coerceAtLeast(64)
            val length = (needed + packet - 1) / packet * packet
            val data = transferIn(device, iface.inEndpoint, length).await<JsAny?>() ?: throw EndOfStreamException("USB 연결이 끊겼습니다")
            buffer += data.toByteArray()
        }
        return buffer.copyOf(count).also { buffer = buffer.copyOfRange(count, buffer.size) }
    }

    override suspend fun write(bytes: ByteArray) {
        if (!transferOut(device, iface.outEndpoint, bytes.toUint8Array()).await<JsBoolean>().toBoolean()) throw EndOfStreamException("USB로 보내지 못했습니다")
        if (bytes.isNotEmpty() && bytes.size % iface.packetSize == 0) transferOut(device, iface.outEndpoint, newBytes(0)).await<JsBoolean>()
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
                openAndClaim(device, iface.configuration, iface.interfaceNumber, iface.alternate, iface.inEndpoint, iface.outEndpoint).await<JsAny?>()
            } catch (e: Throwable) {
                throw IllegalStateException("기기를 열지 못했습니다. 이 컴퓨터에서 adb가 돌고 있다면 `adb kill-server`로 끄고 다시 시도하세요 (${e.message})", e)
            }
            return WebUsbAdbChannel(device, iface)
        }

        /** 기기 목록에 쓰는 serial. USB 일련번호가 없으면 제품 이름으로 만든다. */
        fun serialOf(device: UsbDevice): String = device.serialNumber?.takeIf { it.isNotBlank() } ?: "usb-${device.productName ?: "device"}"

        /** 전에 허용한 기기들. */
        suspend fun permitted(): List<UsbDevice> {
            val array = permittedDevices().await<JsArray<UsbDevice>>()
            return (0 until array.length).mapNotNull { array[it] }
        }
    }
}
