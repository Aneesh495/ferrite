# Ferrite Compression Specification

Ferrite achieves order-of-magnitude storage reduction by applying specialized codecs tailored to numerical time-series patterns.

---

## 1. Gorilla Timestamp Compression (Delta-of-Delta)

Most metrics are collected at regular sampling intervals (e.g. every 10 seconds). Consecutive timestamps exhibit near-zero delta variation.

### Algorithm Description:
Given a sequence of timestamps $t_0, t_1, t_2, \dots$:
1. Store initial timestamp $t_0$ as 64 bits.
2. Store initial delta $D_0 = t_1 - t_0$ as 32 bits (or 14 bits for sub-day intervals).
3. For each subsequent timestamp $t_i$:
   - Compute delta $D_i = t_i - t_{i-1}$.
   - Compute delta-of-delta $DOD = D_i - D_{i-1}$.

### Bit Allocation Encoding:

```
+---------------+---------------+---------------------------------------+
| DOD Range     | Control Bits  | Value Bits & Format                   |
+---------------+---------------+---------------------------------------+
| DOD == 0      | '0' (1 bit)   | None (0 bits)                         |
| [-63, 64]     | '10' (2 bits) | 7 bits (Signed integer representation)|
| [-255, 256]   | '110' (3 bits)| 9 bits (Signed integer representation)|
| [-2047, 2048] | '1110' (4 bits| 12 bits (Signed integer representation|
| Otherwise     | '1111' (4 bits| 32 bits (Raw integer representation)  |
+---------------+---------------+---------------------------------------+
```

When samples arrive on a fixed periodic heartbeat ($DOD = 0$), each timestamp requires exactly **1 bit**, reducing 8-byte integers to a single bit.

---

## 2. Gorilla Floating-Point XOR Compression

Floating-point numbers in time-series metrics frequently share identical exponent and high-order mantissa bits.

### Algorithm Description:
Given a sequence of IEEE 754 64-bit double values $v_0, v_1, v_2, \dots$:
1. Store initial value $v_0$ uncompressed as 64 raw bits.
2. For each subsequent value $v_i$:
   - Compute XOR with previous value: $X = \text{bits}(v_i) \oplus \text{bits}(v_{i-1})$.
   - If $X == 0$ (value is identical): write bit `'0'`.
   - If $X \neq 0$: write bit `'1'`.
     - **Case A**: Leading and trailing zeros in $X$ fit within the previous block's boundaries:
       Write control bit `'0'`, followed by the meaningful bits of $X$.
     - **Case B**: Leading and trailing zeros change:
       Write control bit `'1'`.
       Write 5 bits representing leading zero count (0 to 31).
       Write 6 bits representing meaningful bit length encoded as `length - 1` (0 to 63, allowing full 64-bit lengths).
       Write the meaningful bits of $X$.

---

## 3. String Dictionary & Tag Compression

Telemetry series keys contain repeated string label keys and values (e.g. `env=production`, `region=us-east-1`).

- **Sequential Dense IDs**: Strings are assigned monotonic integer IDs on first encounter.
- **Prefix Sharing**: Common namespaces (e.g. `system.cpu.`, `system.memory.`) are compacted via common prefix extraction.
- **Roaring Bitmaps**: Each unique tag value maintains an inverted index bitmap mapping tag values directly to set memberships of 64-bit series IDs.

---

## 4. Benchmark Measurements

Empirical measurements from `CompressionBenchmark` over 1,000,000 telemetry points:

| Component | Uncompressed Size | Compressed Size | Compression Ratio | Effective Bit Rate |
|:----------|:------------------|:----------------|:------------------|:-------------------|
| Timestamps (10s + jitter) | 7.63 MB | 1.21 MB | 6.29x | 10.17 bits/point |
| Values (Sinusoidal + Noise) | 7.63 MB | 6.79 MB | 1.12x | 57.00 bits/point |
| Constant Steady Value | 7.63 MB | 0.12 MB | 63.50x | 1.01 bits/point |
| Encode Throughput | N/A | N/A | 26.7M points/sec | 37.3 ms for 1M points |
| Decode Throughput | N/A | N/A | 9.8M points/sec | 102.3 ms for 1M points |
