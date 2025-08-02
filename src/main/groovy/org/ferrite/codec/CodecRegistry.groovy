package org.ferrite.codec

import groovy.transform.CompileStatic
import org.ferrite.bitstream.BitReader
import org.ferrite.bitstream.BitWriter

/**
 * Central registry and dispatcher for columnar data encoding and block compression.
 */
@CompileStatic
class CodecRegistry {

    static byte[] compressTimestamps(long[] timestamps) {
        if (timestamps == null || timestamps.length == 0) return new byte[0]
        BitWriter writer = new BitWriter(timestamps.length * 2)
        GorillaTimestampEncoder encoder = new GorillaTimestampEncoder(writer)
        for (int i = 0; i < timestamps.length; i++) {
            encoder.encode(timestamps[i])
        }
        encoder.flush()
        writer.toByteArray()
    }

    static long[] decompressTimestamps(byte[] compressed, int count) {
        if (compressed == null || compressed.length == 0 || count <= 0) return new long[0]
        BitReader reader = new BitReader(compressed)
        GorillaTimestampDecoder decoder = new GorillaTimestampDecoder(reader)
        decoder.decodeAll(count)
    }

    static byte[] compressValues(double[] values) {
        if (values == null || values.length == 0) return new byte[0]
        BitWriter writer = new BitWriter(values.length * 4)
        GorillaValueEncoder encoder = new GorillaValueEncoder(writer)
        for (int i = 0; i < values.length; i++) {
            encoder.encode(values[i])
        }
        encoder.flush()
        writer.toByteArray()
    }

    static double[] decompressValues(byte[] compressed, int count) {
        if (compressed == null || compressed.length == 0 || count <= 0) return new double[0]
        BitReader reader = new BitReader(compressed)
        GorillaValueDecoder decoder = new GorillaValueDecoder(reader)
        decoder.decodeAll(count)
    }

    static byte[] compressBytes(byte[] raw) {
        SnappyCodec.compress(raw)
    }

    static byte[] decompressBytes(byte[] compressed) {
        SnappyCodec.decompress(compressed)
    }

    private CodecRegistry() {}
}
