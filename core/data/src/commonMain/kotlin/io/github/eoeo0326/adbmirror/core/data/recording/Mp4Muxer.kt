package io.github.eoeo0326.adbmirror.core.data.recording

import io.github.eoeo0326.adbmirror.core.data.scrcpy.AnnexB
import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize

/**
 * H.264 패킷을 다시 인코딩하지 않고 fragmented MP4로 감싼다. 캡처 세션 하나(해상도 하나)를 다룬다.
 *
 * - [header]: ftyp + moov(avcC). 첫 key frame 전에 한 번 쓴다.
 * - [addFrame]: 프레임을 모았다가 key frame이 오거나 [fragmentDurationUs]가 지나면 moof + mdat 조각을 돌려준다.
 * - [finish]: 남은 프레임을 마지막 조각으로 내보낸다.
 *
 * 조각을 받는 즉시 파일에 이어 쓰면 녹화 도중 앱이 죽어도 그때까지의 조각은 재생된다.
 * 시간 단위는 µs(timescale 1,000,000)이고, scrcpy PTS를 그대로 쓴다.
 */
class Mp4Muxer(
    private val videoSize: VideoSize,
    config: EncodedPacket,
    private val fragmentDurationUs: Long = 1_000_000,
) {
    private val sps: ByteArray
    private val pps: ByteArray

    init {
        require(config.kind == EncodedPacket.Kind.Config) { "첫 패킷은 config(SPS/PPS)여야 한다" }
        val nals = AnnexB.split(config.data)
        sps = nals.firstOrNull { AnnexB.nalType(it) == AnnexB.NAL_SPS } ?: error("config에 SPS가 없다")
        pps = nals.firstOrNull { AnnexB.nalType(it) == AnnexB.NAL_PPS } ?: error("config에 PPS가 없다")
    }

    private class Sample(val ptsUs: Long, val data: ByteArray, val key: Boolean)

    private val pending = mutableListOf<Sample>()
    private var sequence = 0
    private var firstPtsUs: Long? = null
    private var lastDurationUs = DEFAULT_DURATION_US
    private var started = false

    fun header(): ByteArray = BoxWriter().apply {
        box("ftyp") { fourcc("isom"); u32(0x200); fourcc("isom"); fourcc("iso6"); fourcc("avc1"); fourcc("mp41") }
        box("moov") {
            fullBox("mvhd", 0, 0) {
                u32(0); u32(0) // creation, modification
                u32(TIMESCALE); u32(0) // timescale, duration(조각에서 계산)
                u32(0x00010000); u16(0x0100); zeros(10) // rate 1.0, volume 1.0, reserved
                matrix()
                zeros(24) // pre_defined
                u32(TRACK_ID + 1) // next_track_ID
            }
            box("trak") {
                fullBox("tkhd", 0, 0x3) { // enabled | in movie
                    u32(0); u32(0); u32(TRACK_ID); u32(0); u32(0)
                    zeros(8); u16(0); u16(0); u16(0); u16(0) // reserved, layer, alternate, volume, reserved
                    matrix()
                    u32(videoSize.width shl 16); u32(videoSize.height shl 16)
                }
                box("mdia") {
                    fullBox("mdhd", 0, 0) {
                        u32(0); u32(0); u32(TIMESCALE); u32(0)
                        u16(0x55C4); u16(0) // language "und"
                    }
                    fullBox("hdlr", 0, 0) { u32(0); fourcc("vide"); zeros(12); bytes("VideoHandler".encodeToByteArray()); u8(0) }
                    box("minf") {
                        fullBox("vmhd", 0, 1) { zeros(8) }
                        box("dinf") { fullBox("dref", 0, 0) { u32(1); fullBox("url ", 0, 1) {} } }
                        box("stbl") {
                            fullBox("stsd", 0, 0) {
                                u32(1)
                                box("avc1") {
                                    zeros(6); u16(1) // reserved, data_reference_index
                                    zeros(16)
                                    u16(videoSize.width); u16(videoSize.height)
                                    u32(0x00480000); u32(0x00480000) // 72 dpi
                                    u32(0); u16(1) // reserved, frame_count
                                    zeros(32) // compressorname
                                    u16(0x0018); u16(0xFFFF) // depth, pre_defined
                                    box("avcC") { avcConfiguration() }
                                }
                            }
                            fullBox("stts", 0, 0) { u32(0) }
                            fullBox("stsc", 0, 0) { u32(0) }
                            fullBox("stsz", 0, 0) { u32(0); u32(0) }
                            fullBox("stco", 0, 0) { u32(0) }
                        }
                    }
                }
            }
            box("mvex") {
                fullBox("trex", 0, 0) { u32(TRACK_ID); u32(1); u32(0); u32(0); u32(0) }
            }
        }
    }.toByteArray().also { started = true }

    /** 조각이 완성되면 그 바이트를, 아니면 null. 첫 key frame 전 프레임은 버린다. */
    fun addFrame(packet: EncodedPacket): ByteArray? {
        check(started) { "header()를 먼저 써야 한다" }
        val pts = packet.ptsUs ?: return null
        val key = packet.kind == EncodedPacket.Kind.KeyFrame
        if (firstPtsUs == null && !key) return null // 재생은 key frame부터 시작해야 한다
        if (firstPtsUs == null) firstPtsUs = pts

        val fragment = if (pending.isNotEmpty() && (key || pts - pending.first().ptsUs >= fragmentDurationUs)) {
            flush(nextPtsUs = pts)
        } else {
            null
        }
        pending += Sample(pts, toAvcc(packet.data), key)
        return fragment
    }

    /** 남은 프레임을 마지막 조각으로. 모은 것이 없으면 null. */
    fun finish(): ByteArray? = if (pending.isEmpty()) null else flush(nextPtsUs = null)

    private fun flush(nextPtsUs: Long?): ByteArray {
        val samples = pending.toList()
        pending.clear()
        val durations = samples.indices.map { i ->
            val next = if (i + 1 < samples.size) samples[i + 1].ptsUs else nextPtsUs
            (if (next != null && next > samples[i].ptsUs) next - samples[i].ptsUs else lastDurationUs)
                .also { lastDurationUs = it }
        }
        val baseDecodeTime = samples.first().ptsUs - firstPtsUs!!
        sequence++

        val moof = BoxWriter()
        var dataOffsetField = 0
        moof.box("moof") {
            fullBox("mfhd", 0, 0) { u32(sequence) }
            box("traf") {
                fullBox("tfhd", 0, TFHD_DEFAULT_BASE_IS_MOOF) { u32(TRACK_ID) }
                fullBox("tfdt", 1, 0) { u64(baseDecodeTime) }
                fullBox("trun", 0, TRUN_FLAGS) {
                    u32(samples.size)
                    dataOffsetField = size
                    u32(0) // data_offset: moof 시작 → mdat payload 시작, 아래에서 채움
                    samples.forEachIndexed { i, s ->
                        u32(durations[i])
                        u32(s.data.size)
                        u32(if (s.key) SAMPLE_FLAGS_SYNC else SAMPLE_FLAGS_NON_SYNC)
                    }
                }
            }
        }
        moof.patchU32(dataOffsetField, moof.size + 8)

        val payloadSize = samples.sumOf { it.data.size }
        return BoxWriter().apply {
            bytes(moof.toByteArray())
            u32(8 + payloadSize); fourcc("mdat")
            samples.forEach { bytes(it.data) }
        }.toByteArray()
    }

    private fun BoxWriter.matrix() {
        u32(0x00010000); u32(0); u32(0)
        u32(0); u32(0x00010000); u32(0)
        u32(0); u32(0); u32(0x40000000)
    }

    private fun BoxWriter.avcConfiguration() {
        u8(1) // configurationVersion
        u8(sps[1].toInt() and 0xFF) // AVCProfileIndication
        u8(sps[2].toInt() and 0xFF) // profile_compatibility
        u8(sps[3].toInt() and 0xFF) // AVCLevelIndication
        u8(0xFF) // reserved(6) + lengthSizeMinusOne = 3 (4바이트 길이)
        u8(0xE1) // reserved(3) + numOfSequenceParameterSets = 1
        u16(sps.size); bytes(sps)
        u8(1)
        u16(pps.size); bytes(pps)
    }

    companion object {
        const val TIMESCALE = 1_000_000
        private const val TRACK_ID = 1
        private const val DEFAULT_DURATION_US = 33_333L
        private const val TFHD_DEFAULT_BASE_IS_MOOF = 0x020000
        private const val TRUN_FLAGS = 0x000001 or 0x000100 or 0x000200 or 0x000400 // offset·duration·size·flags
        private const val SAMPLE_FLAGS_SYNC = 0x02000000 // depends_on = 2(다른 프레임에 의존하지 않음)
        private const val SAMPLE_FLAGS_NON_SYNC = 0x01010000 // depends_on = 1, is_non_sync_sample

        /** Annex B → AVCC(NAL마다 4바이트 길이). 파라미터 셋·AUD는 avcC에 있으므로 샘플에서 뺀다. */
        fun toAvcc(annexB: ByteArray): ByteArray {
            val nals = AnnexB.split(annexB).filter { AnnexB.nalType(it) !in DROPPED_NAL_TYPES }
            val out = BoxWriter()
            nals.forEach { out.u32(it.size).bytes(it) }
            return out.toByteArray()
        }

        private val DROPPED_NAL_TYPES = setOf(AnnexB.NAL_SPS, AnnexB.NAL_PPS, 9 /* AUD */)
    }
}
