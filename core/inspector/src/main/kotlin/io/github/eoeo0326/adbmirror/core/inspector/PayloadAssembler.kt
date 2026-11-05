package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.AppInspectionPayload
import java.io.ByteArrayOutputStream

/**
 * `APP_INSPECTION_PAYLOAD` 조각을 `group_id`별로 모은다. 참조되지 않은 그룹이 쌓이지 않도록
 * 최근 [maxGroups]개만 둔다. 한 스레드(이벤트 수집기)에서만 쓴다고 가정하지만 안전하게 동기화한다.
 */
class PayloadAssembler(private val maxGroups: Int = 32) {
    private class Group(count: Int) {
        val chunks = arrayOfNulls<ByteArray>(count)
    }

    private val groups = object : LinkedHashMap<Long, Group>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Group>?) = size > maxGroups
    }

    @Synchronized
    fun add(groupId: Long, payload: AppInspectionPayload) {
        val count = payload.chunkCount.coerceAtLeast(1)
        val group = groups.getOrPut(groupId) { Group(count) }
        val index = payload.chunkIndex
        if (index in group.chunks.indices) group.chunks[index] = payload.chunk.toByteArray()
    }

    /** 모든 조각이 모였으면 이어 붙여 돌려주고 그룹을 지운다. 없거나 빠진 조각이 있으면 null(그룹도 지운다). */
    @Synchronized
    fun take(groupId: Long): ByteArray? {
        val group = groups.remove(groupId) ?: return null
        if (group.chunks.any { it == null }) return null
        val out = ByteArrayOutputStream(group.chunks.sumOf { it!!.size })
        group.chunks.forEach { out.write(it!!) }
        return out.toByteArray()
    }

    @Synchronized
    fun size(): Int = groups.size
}
