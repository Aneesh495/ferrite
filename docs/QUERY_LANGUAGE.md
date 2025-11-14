# Ferrite Query Engine & DSL Specification

Ferrite provides both a declarative Groovy builder DSL and an HTTP REST query interface for evaluating high-dimensional telemetry queries and real-time streaming pipelines.

---

## 1. Groovy Query DSL

The Groovy query DSL leverages closure delegation and builder patterns to express complex analytical queries natively in code.

### Example: Ad-Hoc Filtered Window Rollup
```groovy
import org.ferrite.dsl.FerriteDsl
import org.ferrite.query.QueryPlan

QueryPlan plan = FerriteDsl.query {
    from "system.cpu.idle"
    tag "host", "worker-01"
    tag "region", "us-east-1"
    range "1h"
    min 10.0
    max 95.0
    rate true
    scale 100.0
    every "10s", "avg"
}
```

### Supported Query Directives:
- `from(String metricName)`: Identifies target metric namespace.
- `tag(String key, String value)`: Equality filter on series dimension.
- `tags(Map<String, String> map)`: Bulk dimension matching.
- `range(String humanDuration)`: Relative duration lookup (e.g. `"15m"`, `"1h"`, `"7d"`).
- `between(long startEpochMs, long endEpochMs)`: Explicit closed time interval.
- `min(double threshold)`: Inclusive lower bound filter on values.
- `max(double threshold)`: Inclusive upper bound filter on values.
- `rate(boolean enable)`: Computes instantaneous per-second derivative ($d/dt$).
- `scale(double multiplier)`: Projects values multiplied by scaling factor.
- `every(String window, String agg)`: Tumbling window aggregation (`avg`, `sum`, `min`, `max`, `count`, `stddev`, `last`).

---

## 2. Streaming Telemetry Pipeline DSL

Ferrite allows constructing in-flight reactive telemetry pipelines that filter, map, window, and detect anomalies on incoming metric streams before persisting to disk.

```groovy
import org.ferrite.dsl.FerriteDsl
import org.ferrite.common.MetricPoint

def pipeline = FerriteDsl.stream {
    // 1. Drop idle or zero samples
    filter { it.value > 0.0d }

    // 2. Transform raw value (e.g. bytes to megabytes)
    map { new MetricPoint(it.timestamp, it.value / (1024.0d * 1024.0d)) }

    // 3. Online EWMA anomaly detection (flags points > 3.0 sigmas)
    detectAnomaly(3.0d) { MetricPoint outlier ->
        println "[alert] Anomaly detected: ${outlier}"
    }

    // 4. Tumbling time window
    window(10_000L) { List<MetricPoint> points ->
        double sum = 0.0d
        points.each { sum += it.value }
        new MetricPoint(points[0].timestamp, sum / (double) points.size())
    }

    // 5. Terminal consumer
    sink { MetricPoint point ->
        storageEngine.write(key, point)
    }
}
```

---

## 3. Dynamic Alerting Rule Engine

Alert rules evaluate thresholds over sustained time intervals and track firing state transitions:

```groovy
import org.ferrite.dsl.FerriteDsl
import org.ferrite.dsl.AlertRule

AlertRule rule = FerriteDsl.alert {
    name "MemoryExhaustion"
    metric "system.mem.free"
    tag "cluster", "production"
    condition { it.value < 512.0d }
    sustainedFor "2m"
    severity AlertRule.Severity.CRITICAL
    notify { AlertRule.AlertEvent event ->
        if (event.state == AlertRule.State.FIRING && event.firstFired) {
            println "[CRITICAL] Incident triggered: ${event.ruleName}"
        } else if (event.state == AlertRule.State.OK) {
            println "[RESOLVED] Incident resolved: ${event.ruleName}"
        }
    }
}
```

---

## 4. HTTP REST Query API

Ferrite exposes `/query` for HTTP consumers:

### Raw Series Query:
```http
GET /query?metric=cpu_load&from=1700000000000&to=1700000060000 HTTP/1.1
Host: 127.0.0.1:8086
Accept: application/json
```

**Response (200 OK)**:
```json
{
  "metric": "cpu_load",
  "scanned": 12,
  "executionTimeMs": 0.42,
  "points": [
    { "timestamp": 1700000000000, "value": 42.5 },
    { "timestamp": 1700000010000, "value": 45.1 }
  ]
}
```

### Windowed Aggregate Query:
```http
GET /query?metric=cpu_load&window=1m&agg=avg HTTP/1.1
Host: 127.0.0.1:8086
Accept: application/json
```
