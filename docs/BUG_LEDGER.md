# Ferrite Engineering Bug Ledger & Root Cause Analyses

In accordance with our zero-defect engineering principles, every defect encountered during the design, implementation, and stress testing of Ferrite has been documented with its exact failure reproduction case, root cause analysis, architectural resolution, and permanent regression test case.

---

## Bug 01: `UnsupportedOperationException` on `TagSet.withTag` Mutation

### Description:
Calling `tagSet.withTag(key, value)` threw an `UnsupportedOperationException` at runtime when constructing modified series labels.

### Root Cause Analysis:
`TagSet.toMap()` returned `Collections.unmodifiableMap(...)`. The builder implementation directly attempted to mutate the returned map via `.put(key, value)` rather than cloning it into a mutable `LinkedHashMap`.

### Failing Reproduction Case:
- Test: `TagSetSpec.should support withTag and withoutTag immutably`
- Error: `java.lang.UnsupportedOperationException` at `TagSet.groovy:104`

### Resolution:
Constructed a new `LinkedHashMap<String, String>` before applying alterations:
```groovy
Map<String, String> copy = new LinkedHashMap<String, String>(this.tagMap)
copy.put(key, value)
TagSet.of(copy)
```
Permanent test maintained in `TagSetSpec.groovy`.

---

## Bug 02: Lexer Failure on Hex Long Literal with Trailing Underscore

### Description:
Compilation error in `TimeSeriesKey.groovy`: `Unexpected character: '_'` when initializing a constant hash seed.

### Root Cause Analysis:
The hexadecimal literal `0xAAAAAAAAAAAAAAAA_L` ended with an underscore before the type suffix `L`. Java and Groovy lexical specifications explicitly forbid underscores adjacent to the type suffix or at the end of a numeric literal.

### Resolution:
Corrected literal syntax to `0xAAAAAAAAAAAAAAAA_L` -> `0xAAAA_AAAA_AAAA_AAAAL` or `0xAAAAAAAAAAAAAAAAUL`. Verified via `compileGroovy`.

---

## Bug 03: Gorilla Float Codec 6-Bit Overflow on Length 64

### Description:
Decoding special floating-point numbers (such as `Double.MIN_VALUE` following `Double.NEGATIVE_INFINITY`) corrupted the restored value, causing precision loss.

### Root Cause Analysis:
The meaningful bits length of an XOR result can be exactly 64 bits. However, the Gorilla specification allocates only 6 bits for the length field ($2^6 = 64$ distinct values, covering $0 \dots 63$). Writing 64 into 6 bits caused an arithmetic overflow to 0, completely discarding all 64 bits during decoding.

### Failing Reproduction Case:
- Test: `GorillaCodecSpec.should preserve special floating point values`
- Error: `decoded[i] != values[i]` (got `0.0`, expected `4.9E-324`)

### Resolution:
Encoded `length - 1` into the 6-bit field during compression ($64 - 1 = 63 \le 63$), and added 1 on decompression:
```groovy
// In GorillaValueEncoder:
writer.writeBits((length - 1) & 0x3F, 6)

// In GorillaValueDecoder:
int length = ((int) reader.readBits(6)) + 1
```
Permanent test maintained in `GorillaCodecSpec.groovy`.

---

## Bug 04: Snappy LZ Sliding Window Buffer Overrun on Matches > 67 Bytes

### Description:
Compressing highly repetitive text buffers produced truncated or malformed decompressed output when identical character runs exceeded 64 bytes.

### Root Cause Analysis:
The copy tag in Snappy encodes match lengths using 6 bits ($0 \dots 63$) plus an offset bias of 4 (maximum representable length = 67). When a match exceeded 67 bytes, the encoder attempted to write the raw length without splitting, overflowing the 6-bit tag field.

### Failing Reproduction Case:
- Test: `SnappyCodecSpec.should compress and decompress repeated string text losslessly`

### Resolution:
Capped candidate match lengths to `MAX_COPY_LEN = 67`. Any remaining repeated bytes are emitted as subsequent copy tokens. Permanent test maintained in `SnappyCodecSpec.groovy`.

---

## Bug 05: Metric Name Regular Expression Rejecting Periods and Hyphens

### Description:
Parsing Prometheus or InfluxDB metric names such as `cpu.usage` or `sys-load` failed with `IllegalArgumentException: Invalid metric key syntax`.

### Root Cause Analysis:
The regex in `TimeSeriesKey.groovy` was defined as `^([a-zA-Z0-9_:]+)(?:\\{(.*)\\})?$`. Standard telemetry namespaces commonly use dots (`.`) and hyphens (`-`).

### Failing Reproduction Case:
- Test: `TimeSeriesKeySpec.should parse prometheus style metric key strings`

### Resolution:
Expanded the pattern character class to include dots and hyphens:
```groovy
Pattern KEY_SYNTAX = Pattern.compile("^([a-zA-Z0-9_:.-]+)(?:\\{(.*)\\})?\$")
```
Permanent test maintained in `TimeSeriesKeySpec.groovy`.

---

## Bug 06: `EWMABands` Anomaly Detection Invariance Under Zero Signal Variance

### Description:
Injecting a massive outlier (e.g. `50.0`) into a continuous flatline metric (`10.0`) failed to trigger an anomaly breach.

### Root Cause Analysis:
When baseline observations have zero variance, `variance = 0.0` and `stdDev = 0.0`. The breach condition checked `stdDev > 1e-9d && ...`, preventing any breach from ever being recognized when a signal was completely stable prior to the spike.

### Failing Reproduction Case:
- Test: `AnalyticsSpec.EWMABands should adapt volatility and flag spikes`
- Error: `Condition not satisfied: bands.update(50.0d) == true` (got `false`)

### Resolution:
Added explicit zero-variance divergence handling:
```groovy
if (stdDev > 1e-9d) {
    breach = x > (mean + numSigmas * stdDev) || x < (mean - numSigmas * stdDev)
} else {
    breach = Math.abs(x - mean) > 1e-4d
}
```
Permanent test maintained in `AnalyticsSpec.groovy`.

---

## Bug 07: `StreamPipeline.flush()` Dropping Downstream Window Aggregates

### Description:
Flushing a `StreamPipeline` containing a tumbling window stage aggregated the points but failed to invoke the subsequent `sink` consumer.

### Root Cause Analysis:
The original `flush()` implementation collected output from each stage independently and appended them directly to the master output list, rather than cascading the flushed points through all remaining downstream pipeline stages.

### Failing Reproduction Case:
- Test: `FerriteDslSpec.FerriteDsl streaming pipeline should aggregate points in tumbling windows`
- Error: `windowResults.size() == 2` (got `1`)

### Resolution:
Cascaded any points emitted by `stage.flush()` through all downstream stages ($j = i + 1 \dots N$). Permanent test maintained in `FerriteDslSpec.groovy`.

---

## Bug 08: Race Condition: Concurrently Writing to Read-Only Flushing MemTable

### Description:
Under intensive concurrent writes and background flushes, worker threads encountered:
`java.lang.IllegalStateException: Cannot write to read-only MemTable`.

### Root Cause Analysis:
In `StorageEngine.write()`, a worker thread obtained a reference to `activeMemTable`. Before calling `putAll()`, another thread invoked `triggerFlush()`, which marked `activeMemTable.makeReadOnly()` and swapped in a new active table. The worker thread then invoked `putAll()` on the now read-only table.

### Failing Reproduction Case:
- Test: `ChaosSimulationSpec.Chaos: should withstand high-concurrency multi-threaded writes and compaction`

### Resolution:
Protected `activeMemTable.putAll()` with `flushLock.readLock()`. Because `triggerFlush()` acquires `flushLock.writeLock()`, it is mathematically impossible for an active table to be transitioned to read-only while any worker is inserting into it. Permanent test maintained in `ChaosSimulationSpec.groovy`.

---

## Bug 09: `WALRecoveryReader` Throwing Uncaught Exception on Corrupted Entries

### Description:
Corrupted WAL records resulting from bit rot or torn disk writes caused `WALRecoveryReader` to rethrow `CorruptWALException` rather than truncating at the safe recovery offset.

### Root Cause Analysis:
`WALRecoveryReader` checked `if (buf.readerIndex() == entryStart)` inside the catch block. However, `WALEntry.readFrom` had already advanced `readerIndex` while reading header fields before encountering the CRC mismatch, so the condition evaluated to false and rethrew the exception.

### Failing Reproduction Case:
- Test: `ChaosSimulationSpec.Chaos: should detect bit-rot and torn writes in WAL log files`

### Resolution:
Removed the erroneous index equivalence check. Any `CorruptWALException` during sequential scan indicates corrupted data, and the reader unconditionally truncates the channel back to `entryStart`. Permanent test maintained in `ChaosSimulationSpec.groovy`.
