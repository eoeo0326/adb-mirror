package io.github.eoeo0326.adbmirror.core.data.recording

import io.github.eoeo0326.adbmirror.core.domain.model.VideoInfo

/**
 * [Mp4Muxer]가 만든 fragmented MP4(H.264 트랙 하나)를 읽어 샘플 위치표를 만든다. 웹처럼 플랫폼 디코더가
 * MP4를 직접 열지 못할 때 쓴다. 샘플 데이터는 읽지 않고 위치만 기억해, 큰 녹화도 상자 머리만 읽는다.
 *
 * moov(avc1·avcC)와 moof(tfhd·tfdt·trun)를 읽는다. 앱이 녹화 도중 죽어 끝이 잘린 파일은 온전한 샘플까지만 쓴다.
 */
object Mp4Demuxer {
    /** [offset]부터 [size]바이트(H.264 AVCC 샘플). 시간은 µs이고 첫 샘플이 0이다. */
    class Sample(val offset: Long, val size: Int, val ptsUs: Long, val durationUs: Long, val key: Boolean)

    class Track(
        val width: Int,
        val height: Int,
        /** avcC 상자 본문(AVCDecoderConfigurationRecord). WebCodecs의 `description`으로 그대로 쓴다. */
        val avcC: ByteArray,
        val samples: List<Sample>,
    ) {
        /** WebCodecs 코덱 문자열 `avc1.PPCCLL`(profile·compatibility·level). */
        val codec: String get() = "avc1." + (1..3).joinToString("") { (avcC[it].toInt() and 0xFF).toString(16).padStart(2, '0') }

        val durationUs: Long get() = samples.lastOrNull()?.let { it.ptsUs + it.durationUs } ?: 0

        val info: VideoInfo get() = VideoInfo(durationUs / 1000, width, height)
    }

    /** 파일 전체 크기 [size]와, 파일의 일부를 읽는 [read]로 연다. */
    fun read(size: Long, read: (offset: Long, length: Int) -> ByteArray): Track {
        var moov: ByteArray? = null
        val fragments = mutableListOf<Fragment>()
        var pos = 0L
        while (pos + 8 <= size) {
            val head = read(pos, minOf(16L, size - pos).toInt())
            var boxSize = u32(head, 0)
            val type = fourcc(head, 4)
            var headerSize = 8
            when (boxSize) {
                1L -> { if (head.size < 16) break; boxSize = u64(head, 8); headerSize = 16 }
                0L -> boxSize = size - pos
            }
            if (boxSize < headerSize || pos + boxSize > size) break // 잘린 상자
            when (type) {
                "moov" -> moov = read(pos + headerSize, (boxSize - headerSize).toInt())
                "moof" -> fragments += Fragment(pos, read(pos + headerSize, (boxSize - headerSize).toInt()))
            }
            pos += boxSize
        }
        val header = parseMoov(moov ?: error("moov 상자가 없습니다"))
        val samples = mutableListOf<Sample>()
        for (fragment in fragments) {
            val complete = parseMoof(fragment, header.timescale, size, samples)
            if (!complete) break
        }
        require(samples.isNotEmpty()) { "녹화된 프레임이 없습니다" }
        // 첫 샘플을 0으로 맞춘다(우리 muxer는 이미 0에서 시작한다).
        val origin = samples.first().ptsUs
        val shifted = if (origin == 0L) samples else samples.map { Sample(it.offset, it.size, it.ptsUs - origin, it.durationUs, it.key) }
        return Track(header.width, header.height, header.avcC, shifted)
    }

    private class Fragment(val start: Long, val body: ByteArray)

    private class Header(val width: Int, val height: Int, val timescale: Long, val avcC: ByteArray)

    private class BoxRef(val type: String, val start: Int, val end: Int)

    private fun children(bytes: ByteArray, from: Int, to: Int): List<BoxRef> {
        val list = mutableListOf<BoxRef>()
        var p = from
        while (p + 8 <= to) {
            val size = u32(bytes, p).toInt()
            if (size < 8 || p + size > to) break
            list += BoxRef(fourcc(bytes, p + 4), p + 8, p + size)
            p += size
        }
        return list
    }

    private fun List<BoxRef>.child(type: String) = firstOrNull { it.type == type } ?: error("$type 상자가 없습니다")

    private fun parseMoov(moov: ByteArray): Header {
        val trak = children(moov, 0, moov.size).child("trak")
        val mdia = children(moov, trak.start, trak.end).child("mdia")
        val mdiaChildren = children(moov, mdia.start, mdia.end)
        val mdhd = mdiaChildren.child("mdhd")
        val timescale = if (moov[mdhd.start].toInt() == 1) u32(moov, mdhd.start + 20) else u32(moov, mdhd.start + 12)
        val minf = mdiaChildren.child("minf")
        val stbl = children(moov, minf.start, minf.end).child("stbl")
        val stsd = children(moov, stbl.start, stbl.end).child("stsd")
        // stsd: version·flags(4) + entry_count(4) 뒤에 샘플 엔트리
        val avc1 = children(moov, stsd.start + 8, stsd.end).child("avc1")
        // VisualSampleEntry 고정 필드 78바이트 중 width·height는 24·26
        val width = u16(moov, avc1.start + 24)
        val height = u16(moov, avc1.start + 26)
        val avcC = children(moov, avc1.start + 78, avc1.end).child("avcC")
        require(timescale > 0 && width > 0 && height > 0) { "영상 정보가 잘못됐습니다" }
        return Header(width, height, timescale, moov.copyOfRange(avcC.start, avcC.end))
    }

    /** 샘플을 [out]에 더한다. 파일이 이 조각 중간에서 잘렸으면 false. */
    private fun parseMoof(fragment: Fragment, timescale: Long, fileSize: Long, out: MutableList<Sample>): Boolean {
        val body = fragment.body
        val traf = children(body, 0, body.size).firstOrNull { it.type == "traf" } ?: return true
        val boxes = children(body, traf.start, traf.end)
        val tfhd = boxes.child("tfhd")
        val tfhdFlags = u24(body, tfhd.start + 1)
        var p = tfhd.start + 8 // version·flags, track_ID
        var base = fragment.start // default-base-is-moof 또는 아무것도 없으면 moof 시작
        if (tfhdFlags and 0x1 != 0) { base = u64(body, p); p += 8 }
        if (tfhdFlags and 0x2 != 0) p += 4
        var defaultDuration = 0L
        var defaultSize = 0
        var defaultFlags = 0
        if (tfhdFlags and 0x8 != 0) { defaultDuration = u32(body, p); p += 4 }
        if (tfhdFlags and 0x10 != 0) { defaultSize = u32(body, p).toInt(); p += 4 }
        if (tfhdFlags and 0x20 != 0) { defaultFlags = u32(body, p).toInt() }

        var decodeTime = boxes.firstOrNull { it.type == "tfdt" }?.let { if (body[it.start].toInt() == 1) u64(body, it.start + 4) else u32(body, it.start + 4) } ?: 0L
        for (trun in boxes.filter { it.type == "trun" }) {
            val version = body[trun.start].toInt()
            val flags = u24(body, trun.start + 1)
            val count = u32(body, trun.start + 4).toInt()
            var q = trun.start + 8
            var offset = base
            if (flags and 0x1 != 0) { offset += u32(body, q).toInt(); q += 4 }
            var firstFlags: Int? = null
            if (flags and 0x4 != 0) { firstFlags = u32(body, q).toInt(); q += 4 }
            for (i in 0 until count) {
                val duration = if (flags and 0x100 != 0) u32(body, q).also { q += 4 } else defaultDuration
                val size = if (flags and 0x200 != 0) u32(body, q).toInt().also { q += 4 } else defaultSize
                val sampleFlags = if (flags and 0x400 != 0) u32(body, q).toInt().also { q += 4 } else if (i == 0 && firstFlags != null) firstFlags else defaultFlags
                val cto = if (flags and 0x800 != 0) (if (version == 0) u32(body, q) else u32(body, q).toInt().toLong()).also { q += 4 } else 0L
                if (offset + size > fileSize) return false // 녹화가 이 샘플 도중 끊겼다
                out += Sample(
                    offset = offset,
                    size = size,
                    ptsUs = (decodeTime + cto) * 1_000_000 / timescale,
                    durationUs = duration * 1_000_000 / timescale,
                    key = sampleFlags and SAMPLE_IS_NON_SYNC == 0,
                )
                offset += size
                decodeTime += duration
            }
        }
        return true
    }

    private const val SAMPLE_IS_NON_SYNC = 0x00010000

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun u24(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 16) or u16(b, i + 1)
    private fun u32(b: ByteArray, i: Int): Long = (0 until 4).fold(0L) { a, k -> (a shl 8) or (b[i + k].toLong() and 0xFF) }
    private fun u64(b: ByteArray, i: Int): Long = (u32(b, i) shl 32) or u32(b, i + 4)
    private fun fourcc(b: ByteArray, i: Int) = (i until i + 4).map { (b[it].toInt() and 0xFF).toChar() }.joinToString("")
}
