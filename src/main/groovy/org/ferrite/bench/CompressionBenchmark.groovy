package org.ferrite.bench

import groovy.transform.CompileStatic
import org.ferrite.bitstream.BitReader
import org.ferrite.bitstream.BitWriter
import org.ferrite.codec.GorillaTimestampDecoder
import org.ferrite.codec.GorillaTimestampEncoder
import org.ferrite.codec.GorillaValueDecoder
import org.ferrite.codec.GorillaValueEncoder

/**
 * Microbenchmark harness measuring throughput and compression ratios
 * of the Gorilla XOR and delta-of-delta encoding engine.
 */
@CompileStatic
class CompressionBenchmark {

    static void main(String[] args) {
        int n = 1_000_000
        println "[benchmark] Generating ${n} telemetry samples..."

        long[] timestamps = new long[n]
        double[] values = new double[n]
        long baseTime = 1700000000000L

        Random rng = new Random(42L)
        for (int i = 0; i < n; i++) {
            // Realistic telemetry: 10s base interval with random +/- 50ms jitter
            timestamps[i] = baseTime + (i * 10000L) + (long) (rng.nextInt(100) - 50)
            // Realistic sinusoidal cpu metric with noise
            values[i] = 50.0d + 20.0d * Math.sin((double) i / 500.0d) + rng.nextGaussian() * 2.0d
        }

        println "[benchmark] Benchmarking Gorilla Timestamp Compression..."
        long t0 = System.nanoTime()
        BitWriter tsWriter = new BitWriter()
        GorillaTimestampEncoder tsEnc = new GorillaTimestampEncoder(tsWriter)
        for (int i = 0; i < n; i++) {
            tsEnc.encode(timestamps[i])
        }
        tsEnc.flush()
        byte[] tsBytes = tsWriter.toByteArray()
        long t1 = System.nanoTime()
        double tsDurationMs = (double) (t1 - t0) / 1_000_000.0d
        double tsThroughput = (double) n / (tsDurationMs / 1000.0d)

        double rawTsBytes = (double) (n * 8)
        double tsRatio = rawTsBytes / (double) tsBytes.length

        println String.format(
                "  Timestamps: Raw = %.2f MB, Compressed = %.2f MB (%.2fx ratio, %.2f bits/point)",
                rawTsBytes / (1024.0d * 1024.0d),
                (double) tsBytes.length / (1024.0d * 1024.0d),
                tsRatio,
                ((double) tsBytes.length * 8.0d) / (double) n
        )
        println String.format("  Encode Speed: %.2f ops/sec (%.2f ms total)", tsThroughput, tsDurationMs)

        println "[benchmark] Benchmarking Gorilla Floating-Point XOR Compression..."
        long v0 = System.nanoTime()
        BitWriter valWriter = new BitWriter()
        GorillaValueEncoder valEnc = new GorillaValueEncoder(valWriter)
        for (int i = 0; i < n; i++) {
            valEnc.encode(values[i])
        }
        valEnc.flush()
        byte[] valBytes = valWriter.toByteArray()
        long v1 = System.nanoTime()
        double valDurationMs = (double) (v1 - v0) / 1_000_000.0d
        double valThroughput = (double) n / (valDurationMs / 1000.0d)

        double rawValBytes = (double) (n * 8)
        double valRatio = rawValBytes / (double) valBytes.length

        println String.format(
                "  Values:     Raw = %.2f MB, Compressed = %.2f MB (%.2fx ratio, %.2f bits/point)",
                rawValBytes / (1024.0d * 1024.0d),
                (double) valBytes.length / (1024.0d * 1024.0d),
                valRatio,
                ((double) valBytes.length * 8.0d) / (double) n
        )
        println String.format("  Encode Speed: %.2f ops/sec (%.2f ms total)", valThroughput, valDurationMs)

        println "[benchmark] Benchmarking Decompression Throughput..."
        long d0 = System.nanoTime()
        BitReader tsReader = new BitReader(tsBytes)
        GorillaTimestampDecoder tsDec = new GorillaTimestampDecoder(tsReader)
        long[] recoveredTs = tsDec.decodeAll(n)

        BitReader valReader = new BitReader(valBytes)
        GorillaValueDecoder valDec = new GorillaValueDecoder(valReader)
        double[] recoveredVal = valDec.decodeAll(n)
        long d1 = System.nanoTime()
        double decDurationMs = (double) (d1 - d0) / 1_000_000.0d
        double decThroughput = (double) n / (decDurationMs / 1000.0d)

        println String.format("  Decode Speed: %.2f ops/sec (%.2f ms total)", decThroughput, decDurationMs)
        println "[benchmark] Compression benchmark completed successfully."
    }
}
