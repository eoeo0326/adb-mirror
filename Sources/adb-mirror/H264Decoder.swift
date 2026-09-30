import CoreMedia
import Foundation

/// Annex B H.264 패킷을 AVSampleBufferDisplayLayer에 넣을 수 있는 CMSampleBuffer로 바꾼다.
/// 실제 디코딩은 디스플레이 레이어(VideoToolbox)가 한다.
final class H264Decoder {
    private var formatDescription: CMVideoFormatDescription?

    /// SPS/PPS가 담긴 config 패킷으로 포맷을 갱신한다.
    func updateConfig(_ data: Data) {
        let nals = Self.splitAnnexB(data)
        guard let sps = nals.first(where: { Self.nalType($0) == 7 }),
              let pps = nals.first(where: { Self.nalType($0) == 8 }) else { return }
        formatDescription = Self.makeFormat(sps: sps, pps: pps)
    }

    /// 영상 패킷을 샘플 버퍼로 변환. 포맷이 아직 없으면 nil.
    func sampleBuffer(for data: Data) -> CMSampleBuffer? {
        var nals = Self.splitAnnexB(data)
        // 인코더가 key frame 앞에 SPS/PPS를 다시 붙이는 경우가 있어 포맷만 갱신하고 빼낸다.
        if let sps = nals.first(where: { Self.nalType($0) == 7 }),
           let pps = nals.first(where: { Self.nalType($0) == 8 }) {
            formatDescription = Self.makeFormat(sps: sps, pps: pps)
        }
        nals.removeAll { [7, 8].contains(Self.nalType($0)) }
        guard let format = formatDescription, !nals.isEmpty else { return nil }

        // AVCC: NAL마다 4바이트 big-endian 길이 prefix.
        var avcc = Data(capacity: data.count + nals.count * 4)
        for nal in nals {
            var len = UInt32(nal.count).bigEndian
            withUnsafeBytes(of: &len) { avcc.append(contentsOf: $0) }
            avcc.append(nal)
        }

        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: avcc.count,
            blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0,
            dataLength: avcc.count, flags: 0, blockBufferOut: &block) == noErr,
            let block else { return nil }
        let copied = avcc.withUnsafeBytes {
            CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: block,
                                          offsetIntoDestination: 0, dataLength: avcc.count)
        }
        guard copied == noErr else { return nil }

        var sample: CMSampleBuffer?
        var size = avcc.count
        guard CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format,
            sampleCount: 1, sampleTimingEntryCount: 0, sampleTimingArray: nil,
            sampleSizeEntryCount: 1, sampleSizeArray: &size, sampleBufferOut: &sample) == noErr,
            let sample else { return nil }

        // 타임베이스 없이 받는 즉시 표시.
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict,
                                 Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return sample
    }

    private static func makeFormat(sps: Data, pps: Data) -> CMVideoFormatDescription? {
        var format: CMVideoFormatDescription?
        let status = sps.withUnsafeBytes { spsBuf in
            pps.withUnsafeBytes { ppsBuf in
                let pointers = [spsBuf.bindMemory(to: UInt8.self).baseAddress!,
                                ppsBuf.bindMemory(to: UInt8.self).baseAddress!]
                let sizes = [sps.count, pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault, parameterSetCount: 2,
                    parameterSetPointers: pointers, parameterSetSizes: sizes,
                    nalUnitHeaderLength: 4, formatDescriptionOut: &format)
            }
        }
        return status == noErr ? format : nil
    }

    private static func nalType(_ nal: Data) -> UInt8 {
        nal.first.map { $0 & 0x1F } ?? 0
    }

    /// 00 00 01 / 00 00 00 01 start code 기준으로 NAL 본문만 잘라낸다.
    static func splitAnnexB(_ data: Data) -> [Data] {
        let bytes = [UInt8](data)
        var starts: [(codeStart: Int, nalStart: Int)] = []
        var i = 0
        while i + 2 < bytes.count {
            if bytes[i] == 0, bytes[i + 1] == 0, bytes[i + 2] == 1 {
                let codeStart = (i > 0 && bytes[i - 1] == 0) ? i - 1 : i
                starts.append((codeStart, i + 3))
                i += 3
            } else {
                i += 1
            }
        }
        return starts.enumerated().compactMap { idx, s in
            let end = idx + 1 < starts.count ? starts[idx + 1].codeStart : bytes.count
            return end > s.nalStart ? Data(bytes[s.nalStart..<end]) : nil
        }
    }
}
