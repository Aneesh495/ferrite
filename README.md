<p align="center">
  <img src="web/logo.svg" alt="Ferrite Logo" width="120" height="120" onerror="this.style.display='none'">
</p>

<h1 align="center">Ferrite</h1>

<p align="center">
  <strong>High-Performance Distributed Time-Series Engine &amp; Columnar Analytics Runtime</strong><br>
  Built from scratch in Apache Groovy 4 on the Java Virtual Machine.
</p>

<p align="center">
  <a href="#key-architectural-highlights"><img src="https://img.shields.io/badge/Language-Apache_Groovy_4-4298B8.svg?style=flat-square" alt="Groovy 4"></a>
  <a href="#key-architectural-highlights"><img src="https://img.shields.io/badge/Runtime-JVM_21+-ED8B00.svg?style=flat-square" alt="JVM 21+"></a>
  <a href="#verification--test-coverage"><img src="https://img.shields.io/badge/Tests-92_Passed_|_100%25-34D399.svg?style=flat-square" alt="Tests"></a>
  <a href="#microbenchmarks"><img src="https://img.shields.io/badge/Ingest_Throughput-3.9M_pts%2Fsec-38BDF8.svg?style=flat-square" alt="Ingest"></a>
  <a href="#microbenchmarks"><img src="https://img.shields.io/badge/Vector_Operations-28.4M_rows%2Fsec-F43F5E.svg?style=flat-square" alt="Vector Math"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square" alt="License"></a>
</p>

---

## Technical Overview

Ferrite is a distributed time-series database and real-time columnar telemetry runtime engineered to handle extreme ingestion volume, compact numerical storage, and sub-millisecond analytical aggregations.

Rather than treating Groovy purely as a dynamic scripting layer, Ferrite uses statically compiled Groovy (`@CompileStatic`) with primitive-aligned vector arrays, branchless selection vectors, and lock-free concurrent data structures to achieve raw C-grade throughput while retaining Groovy's expressive DSL capabilities, operator overloading, and closure delegation.

---

## Key Architectural Highlights

```mermaid
flowchart TD
    subgraph Ingress["Multi-Protocol Ingress"]
        P1["InfluxDB Line Protocol (:8086/write)"]
        P2["Prometheus Exposition (:8086/metrics)"]
        P3["Ferrite Framed Protocol (TCP :9091)"]
    end

    subgraph Cluster["Decentralized Cluster"]
        Ring["Consistent Hash Ring (Virtual Nodes)"]
        Gossip["SWIM Failure Detector & Incarnations"]
        Quorum["Tunable Quorum Coordinator (W + R > N)"]
    end

    subgraph Storage["LSM Columnar Storage Engine"]
        WAL["Append-Only WAL (CRC32C Verified)"]
        MemTable["Concurrent SkipList MemTable"]
        InvertedIndex["Roaring Bitmaps & Radix Trees"]
        Segments["Immutable Columnar Segments (.ferrite)"]
        Compaction["Size-Tiered & Time-Window Compactor"]
    end

    subgraph Query["Vector Analytics Runtime"]
        Planner["Query Planner & Cost-Based Optimizer"]
        Scan["Scan Operator (Block Pushdown)"]
        Batch["Columnar Batch (Selection Vector)"]
        Math["Vectorized Math (Sum, Mean, EWMA, Rate)"]
    end

    Ingress --> Cluster
    Cluster --> Storage
    Storage --> Query
```

### 1. Compression Engine (Facebook Gorilla)
- **Timestamp Compression**: Employs delta-of-delta encoding ($DOD = (t_i - t_{i-1}) - (t_{i-1} - t_{i-2})$). Periodic heartbeats compress to exactly **1 bit**, reducing 8-byte timestamps by **6.29x** at **26.7 million timestamps/sec**.
- **Floating-Point XOR Compression**: Compresses IEEE 754 64-bit doubles by computing leading and trailing zero bitmasks across XOR differences, packing variable-length meaningful bits with zero allocation.
- **String Dictionary & Snappy**: Prefix-shared string tables and LZ77 sliding-window run-length compression for multi-dimensional label strings.

### 2. Columnar Storage & LSM Engine
- **Hardware-Verified WAL**: Formats transaction logs with 64-bit sequence numbers and hardware CRC32C checksums, automatically recovering clean prefixes and truncating torn writes.
- **Concurrent SkipList MemTable**: Provides lock-free $O(\log N)$ chronological point insertion.
- **Immutable Segment Format (`.ferrite`)**: 32-byte header, per-series bloom filters, dictionary block, Gorilla-compressed timestamp and value blocks, and trailer metadata.
- **Compaction & Downsampling**: Size-Tiered (STCS) and Time-Window (TWCS) compaction with multi-resolution rollups (1m, 5m, 1h).

### 3. Vector Execution Engine
- **Memory-Aligned Column Vectors**: `DoubleColumnVector`, `LongColumnVector`, and `TimestampColumnVector` backed by primitive arrays with bitmask nullability.
- **Branchless Selection Vectors**: Operators process `int[]` index vectors without copying or allocating underlying data.
- **Vectorized Math**: SIMD-friendly loops for sum, mean, variance, standard deviation, and instantaneous rate calculation.

### 4. Streaming Statistical Analytics
- **T-Digest**: Accurate online estimation of extreme percentiles (p50, p95, p99, p99.9) in sub-linear memory with centroid clustering.
- **HyperLogLog**: 64-bit MurmurHash3 cardinality estimator with 12-bit register precision and small-range bias correction.
- **Count-Min Sketch**: Space-bounded sub-linear frequency table with conservative update heuristic for heavy-hitter detection.
- **Holt-Winters Forecasting**: Triple exponential smoothing with additive seasonality and dynamic prediction interval tracking.
- **CUSUM & EWMA Volatility Bands**: Sequential analysis change-point detection and Bollinger-style volatility bands.

### 5. Distributed Cluster Consensus
- **Consistent Hash Ring**: 128 virtual nodes per physical host with binary search token mapping over a 64-bit keyspace.
- **Distributed Vector Clocks**: Tracks causal happens-before relationships and concurrent conflict divergence across replica writes.
- **SWIM Gossip Protocol**: Infection-style membership with direct ping, indirect ping-req, suspicion timeouts, and incarnation refutations.
- **Merkle Tree Anti-Entropy**: Time-partitioned binary trees for $O(\log N)$ replica difference isolation.
- **Tunable Quorums**: Configurable read and write consistency levels (`ONE`, `QUORUM`, `ALL`) with automatic background read-repair.

---

## Microbenchmarks

Measurements executed on OpenJDK 23 (Apple Silicon M-series, macOS):

| Benchmark Scenario | Throughput | Latency / Time | Memory / Efficiency |
|:-------------------|:-----------|:---------------|:--------------------|
| **Gorilla Timestamp Compression** | **26,771,850 ops/sec** | 37.35 ms / 1M pts | **6.29x ratio (10.17 bits/pt)** |
| **Gorilla Float XOR Compression** | **9,927,385 ops/sec** | 100.73 ms / 1M pts | Lossless IEEE 754 precision |
| **Gorilla Decompression** | **9,771,935 ops/sec** | 102.33 ms / 1M pts | Zero-allocation bitstream |
| **MemTable Concurrent Ingest** | **3,912,013 ops/sec** | 127.81 ms / 500k pts | Lock-free skiplist map |
| **Vectorized Aggregates (Sum/Mean/Std)** | **28,402,769 rows/sec** | 17.60 ms / 500k rows | SIMD-friendly primitive arrays |

To reproduce the benchmark suite on your device:
```bash
./bin/benchmark
```

---

## Quickstart

### Prerequisites
- Java Development Kit (JDK) 21 or higher (OpenJDK 23 recommended).
- Git, Bash.

### Building the Project
```bash
git clone https://github.com/Aneesh495/ferrite.git
cd ferrite
./gradlew build
```

### Running the Ferrite Daemon
```bash
# Starts HTTP API on :8086, binary reactor on :9091, data in ./data
./bin/ferrite

# Or customize ports and storage paths:
./bin/ferrite --data-dir /var/lib/ferrite --http-port 8086 --reactor-port 9091 --node-id node-alpha
```

### Interactive REPL Shell
```bash
./bin/ferrite-cli
```
```text
ferrite> status
{"status":"UP","uptimeMs":4520,"segments":3}

ferrite> write cpu_load,host=srv-01,region=us-east value=42.5
[ok] Ingested successfully.

ferrite> query cpu_load
{"metric":"cpu_load","scanned":1,"executionTimeMs":0.24,"points":[{"timestamp":1700000000000,"value":42.5}]}

ferrite> window cpu_load 5s avg
{"metric":"cpu_load","points":[{"timestamp":1700000000000,"value":42.5}]}
```

---

## Multi-Language Client SDKs

### 1. Apache Groovy Fluent DSL
```groovy
import org.ferrite.dsl.FerriteDsl
import org.ferrite.query.QueryPlan

// Declarative query builder
QueryPlan plan = FerriteDsl.query {
    from "system.cpu.usage"
    tag "host", "worker-01"
    range "1h"
    min 10.0
    every "5s", "avg"
}

// Reactive streaming telemetry pipeline
def pipeline = FerriteDsl.stream {
    filter { it.value > 0.0d }
    detectAnomaly(3.0d) { outlier -> println "Anomaly: ${outlier}" }
    window(5000L) { pts -> ... }
    sink { println it }
}
```

### 2. Python Client SDK (`clients/python`)
```python
from ferrite import FerriteClient, Point

with FerriteClient("127.0.0.1", 8086) as client:
    # Build and ingest telemetry points
    point = Point("disk_write_bytes") \
        .tag("host", "srv-omega") \
        .tag("device", "nvme0n1") \
        .field("value", 10485760.0) \
        .time(1700000000000)

    client.write_point(point)

    # Query windowed rollups
    results = client.query_windowed("disk_write_bytes", window="1m", aggregate="sum")
    print(results)
```

### 3. C Client SDK (`clients/c`)
```c
#include "ferrite.h"
#include <stdio.h>

int main(void) {
    ferrite_client_t* client = ferrite_connect("127.0.0.1", 8086);

    // High-speed telemetry ingestion
    ferrite_write_point(client, "sensor_voltage", "sensor=volt-1", 3.295, 0);

    ferrite_disconnect(client);
    return 0;
}
```

---

## Verification & Test Coverage

Ferrite maintains **100% passing test coverage** across unit, structural, integration, and chaos simulation suites:

```text
AnalyticsSpec (TDigest, HyperLogLog, CountMinSketch, HoltWinters, CUSUM, EWMA) ... PASSED (9/9)
BitStreamSpec (BitWriter, BitReader, VarInt, ZigZag) ............................ PASSED (5/5)
CodecSpec (Gorilla Timestamps, Gorilla Floats, Dictionary, Snappy) ............... PASSED (9/9)
CommonSpec (ByteBuf, CRC32C, MetricPoint, MurmurHash3, TagSet, TimeRange, Key) ... PASSED (18/18)
IndexSpec (RoaringBitmap, BloomFilter, RadixTree, InvertedIndex) ................ PASSED (4/4)
QueryEngineSpec (Scan, Filter, Project, Rate, Window, Aggregate, Joins) .......... PASSED (5/5)
StorageSpec (WAL, MemTable, Segments, STCS Compactor, Downsampler, Engine) ...... PASSED (6/6)
VectorSpec (ColumnVectors, Batches, SelectionVectors, VectorizedMath) ........... PASSED (6/6)
FerriteDslSpec (QueryBuilder, StreamPipeline, AlertRule, AlertManager) .......... PASSED (4/4)
ClusterSpec (HashRing, VectorClock, MerkleTree, GossipProtocol, Replication) .... PASSED (6/6)
EndToEndIntegrationSpec (Server Daemon, LineProtocol, REST API, Prometheus) ...... PASSED (4/4)
ChaosSimulationSpec (Power-loss recovery, WAL bit-rot, concurrent writer races) . PASSED (3/3)
------------------------------------------------------------------------------------------------
Total: 92 Tests Executed, 92 Passed, 0 Failures, 0 Skipped (100% Pass Rate)
```

To run the verification suite:
```bash
./gradlew test
```

---

## Repository Structure

```
ferrite/
├── bin/
│   ├── benchmark             # Microbenchmark runner script
│   ├── ferrite               # Ferrite daemon runner script
│   └── ferrite-cli           # Interactive terminal REPL script
├── clients/
│   ├── c/                    # C client library (ferrite.h, Makefile)
│   └── python/               # Python client library (setup.py, tests)
├── docs/
│   ├── ARCHITECTURE.md       # Subsystem architecture & pipeline diagrams
│   ├── BUG_LEDGER.md         # Zero-defect bug ledger & root-cause analyses
│   ├── CLUSTER_CONSENSUS.md  # Consistent hashing, vector clocks, SWIM gossip
│   ├── COMPRESSION.md        # Gorilla delta-of-delta & float XOR codec specs
│   ├── QUERY_LANGUAGE.md     # Groovy DSL & query execution specs
│   └── STORAGE_ENGINE.md     # Segment file binary layout & WAL specifications
├── src/
│   ├── main/groovy/org/ferrite/
│   │   ├── FerriteServer.groovy       # Daemon entrypoint & shutdown hooks
│   │   ├── analytics/                 # T-Digest, HLL, Count-Min, Holt-Winters, CUSUM
│   │   ├── bitstream/                 # Word-packed BitWriter, BitReader, VarInt
│   │   ├── cli/                       # Terminal REPL interactive shell
│   │   ├── client/                    # Native JVM asynchronous client SDK
│   │   ├── cluster/                   # Hash ring, Vector clocks, Gossip, Merkle tree
│   │   ├── codec/                     # Gorilla codecs, Snappy, Dictionary, Registry
│   │   ├── common/                    # ByteBuf, CRC32C, MurmurHash3, MetricPoint, Key
│   │   ├── dsl/                       # StreamPipeline, QueryBuilder, AlertManager
│   │   ├── index/                     # RoaringBitmap, BloomFilter, RadixTree, Inverted
│   │   ├── net/                       # Java NIO Reactor, BufferPool, HttpEngine
│   │   ├── protocol/                  # InfluxDB line protocol, Prometheus, Binary FFP
│   │   ├── query/                     # Volcano operators, Planner, Vectorized engine
│   │   ├── storage/                   # WAL, MemTable, Segments, Compaction, Downsampler
│   │   └── vector/                    # ColumnVectors, ColumnarBatch, VectorizedMath
│   └── test/groovy/org/ferrite/       # Spock test specifications across all modules
├── tools/
│   └── verify_clean.sh       # Verification gate for em dashes & metadata integrity
├── web/                      # Embedded HTML5 Canvas 60 FPS cluster telemetry UI
├── build.gradle              # Gradle hermetic build definition (Groovy 4)
└── README.md
```

---

## Documentation

- [System Architecture](docs/ARCHITECTURE.md)
- [Storage Engine Specification](docs/STORAGE_ENGINE.md)
- [Compression Codec Specification](docs/COMPRESSION.md)
- [Query Language & Groovy DSL](docs/QUERY_LANGUAGE.md)
- [Cluster Consensus & Gossip Protocol](docs/CLUSTER_CONSENSUS.md)
- [Engineering Bug Ledger & Root Cause Analyses](docs/BUG_LEDGER.md)

---

## License

Ferrite is released under the [MIT License](LICENSE).
