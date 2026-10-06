@file:OptIn(ExperimentalWasmJsInterop::class, UnsafeWasmMemoryApi::class)

package io.github.eoeo0326.adbmirror.core.data.web

import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.wasmMemory
import kotlin.wasm.unsafe.withScopedMemoryAllocator

// JS 배열과 Kotlin 배열 사이를 원소마다 JS를 부르지 않고 Wasm 메모리를 거쳐 한 번에 옮긴다.
// 프레임 픽셀처럼 큰 배열을 원소 단위 호출로 옮기면 너무 느리다.

@JsFun("(mem, addr, src) => { new Uint8Array(mem.buffer, addr, src.byteLength).set(new Uint8Array(src.buffer, src.byteOffset, src.byteLength)); }")
private external fun copyToWasm(memory: JsAny, address: Int, source: JsAny)

@JsFun("(mem, addr, length) => new Uint8Array(mem.buffer.slice(addr, addr + length))")
private external fun copyFromWasm(memory: JsAny, address: Int, length: Int): JsAny

@JsFun("(a) => a.byteLength")
internal external fun byteLengthOf(array: JsAny): Int

@JsFun("(a, start, end) => a.subarray(start, end)")
internal external fun subarrayOf(array: JsAny, start: Int, end: Int): JsAny

/** JS 바이트 배열(Uint8Array·Int32Array 등 TypedArray) 전체를 Kotlin [ByteArray]로. */
internal fun JsAny.toByteArray(): ByteArray {
    val length = byteLengthOf(this)
    if (length == 0) return ByteArray(0)
    return withScopedMemoryAllocator { allocator ->
        val p = allocator.allocate(length)
        copyToWasm(wasmMemory, p.address.toInt(), this)
        ByteArray(length) { (p + it).loadByte() }
    }
}

/** JS Int32Array 전체를 Kotlin [IntArray]로(little-endian). */
internal fun JsAny.toIntArray(): IntArray {
    val length = byteLengthOf(this) / 4
    if (length == 0) return IntArray(0)
    return withScopedMemoryAllocator { allocator ->
        val p = allocator.allocate(length * 4)
        copyToWasm(wasmMemory, p.address.toInt(), this)
        IntArray(length) { (p + it * 4).loadInt() }
    }
}

/** Kotlin [ByteArray] → 새 JS Uint8Array. */
internal fun ByteArray.toUint8Array(): JsAny {
    if (isEmpty()) return copyFromWasm(wasmMemory, 0, 0)
    return withScopedMemoryAllocator { allocator ->
        val p = allocator.allocate(size)
        forEachIndexed { i, b -> (p + i).storeByte(b) }
        copyFromWasm(wasmMemory, p.address.toInt(), size)
    }
}

/** Kotlin [IntArray] → 새 JS Uint8Array(원소마다 little-endian 4바이트). */
internal fun IntArray.toUint8Array(): JsAny = withScopedMemoryAllocator { allocator ->
    val p = allocator.allocate(size * 4)
    forEachIndexed { i, v -> (p + i * 4).storeInt(v) }
    copyFromWasm(wasmMemory, p.address.toInt(), size * 4)
}

private operator fun Pointer.plus(offset: Int): Pointer = Pointer(address + offset.toUInt())
