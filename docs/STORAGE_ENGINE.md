# Ferrite Storage Engine Specification

The Ferrite storage engine combines log-structured merge principles (LSM) with columnar block encoding optimized specifically for timestamped telemetry and continuous metrics.

---

## 1. Segment File Binary Layout (`.ferrite`)

Segment files are immutable on-disk artifacts named `seg-<generation>.ferrite`.

```
+---------------------------------------------------------------+
|                      Header (32 Bytes)                        |
|  - Magic Number: 0x46455252 ("FERR")                          |
|  - File Format Version: 0x0001                                |
|  - Flags: Bit 0 = Snappy, Bit 1 = Gorilla                     |
|  - Minimum Timestamp: 64-bit Big-Endian Long                  |
|  - Maximum Timestamp: 64-bit Big-Endian Long                  |
|  - Series Count: 32-bit Integer                               |
|  - Total Point Count: 64-bit Integer                          |
+---------------------------------------------------------------+
|                    Series Index Directory                     |
|  For each series:                                             |
|    - Series ID: 64-bit MurmurHash3 Hash                       |
|    - String Key Length & UTF-8 Bytes                          |
|    - Bloom Filter Bitset (1024 bits per series)               |
|    - Timestamp Block Offset: 64-bit File Offset               |
|    - Timestamp Block Length: 32-bit Integer                   |
|    - Value Block Offset: 64-bit File Offset                   |
|    - Value Block Length: 32-bit Integer                       |
|    - Point Count: 32-bit Integer                              |
|    - Summary Statistics: Min (f64), Max (f64), Sum (f64)      |
+---------------------------------------------------------------+
|                    Compressed Data Blocks                     |
|  - Gorilla Timestamp Block (Delta-of-Delta Bitstream)         |
|  - Gorilla Value Block (XOR Floating-Point Bitstream)         |
+---------------------------------------------------------------+
|                       Trailer (8 Bytes)                       |
|  - Index Directory File Offset: 64-bit Integer                |
|  - CRC32C File Checksum: 32-bit Integer                       |
+---------------------------------------------------------------+
```

---

## 2. Write-Ahead Log (WAL)

The write-ahead log ensures zero data loss during power disruptions or sudden process termination.

### Frame Layout:
1. **Magic**: 2 bytes (`0x5741` = 'WA')
2. **Sequence Number**: 8 bytes (monotonic transaction ID)
3. **Entry Type**: 1 byte (`0x01` = Metric Points, `0x02` = Checkpoint, `0x03` = Drop Series)
4. **Series Name Length**: 2 bytes
5. **Series Name**: UTF-8 bytes
6. **Tag Count**: 2 bytes (followed by key-value pairs)
7. **Point Count**: 4 bytes
8. **Point Payload**: Sequence of `(timestamp: long, value: double)` pairs
9. **CRC32C**: 4-byte hardware checksum calculated over fields 1 through 8

### Recovery and Torn-Write Truncation:
During recovery, `WALRecoveryReader` processes logs sequentially. If a byte discrepancy or CRC mismatch occurs (for instance, when power failure interrupts an in-flight write), the reader immediately identifies the corrupted record offset, truncates the damaged suffix using channel truncation, and successfully returns all clean transactions committed before the crash.

---

## 3. Compaction Engine

Ferrite implements two distinct background compaction strategies:

### Size-Tiered Compaction Strategy (STCS):
- Groups segment files into geometric size buckets (e.g. 4 segments of ~16MB, 4 of ~64MB).
- Merges overlapping segments within a tier into a single higher-tier segment.
- Eliminates duplicate timestamp keys and combines sparse inverted index entries.

### Time-Window Compaction Strategy (TWCS):
- Partitions segments into discrete time windows (e.g. 1 hour, 1 day).
- Once a time window has closed and no new points are expected, historical segments within that window are consolidated once and for all.
- Prepares historical data for irreversible multi-resolution downsampling.

---

## 4. Multi-Resolution Downsampling

Historical telemetry is downsampled automatically to preserve disk capacity while retaining analytical utility:

| Resolution Level | Retention Period | Rollup Frequency | Aggregate Statistics Stored |
|:-----------------|:-----------------|:-----------------|:----------------------------|
| Raw High-Res     | 7 Days           | 10ms to 1s       | Exact floating point values |
| 1-Minute Rollup  | 30 Days          | 60 Seconds       | Count, Sum, Min, Max, Last  |
| 5-Minute Rollup  | 90 Days          | 300 Seconds      | Count, Sum, Min, Max, Last  |
| 1-Hour Rollup    | 365 Days         | 3600 Seconds     | Count, Sum, Min, Max, Last  |
