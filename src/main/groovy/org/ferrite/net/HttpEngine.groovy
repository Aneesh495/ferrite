package org.ferrite.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import groovy.transform.CompileStatic
import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeRange
import org.ferrite.common.TimeSeriesKey
import org.ferrite.protocol.LineProtocolParser
import org.ferrite.protocol.PrometheusParser
import org.ferrite.query.AggregationType
import org.ferrite.query.QueryEngine
import org.ferrite.query.QueryResult
import org.ferrite.storage.StorageEngine
import org.ferrite.storage.StorageStats

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Lightweight HTTP REST and ingestion server for Ferrite.
 * Exposes Prometheus scraping (/metrics), Line Protocol ingestion (/write),
 * time-series query evaluation (/query), and node health status (/health).
 */
@CompileStatic
class HttpEngine {

    private final String host
    private final int port
    private final StorageEngine storageEngine
    private final QueryEngine queryEngine
    private HttpServer server
    private final long startupTimestamp = System.currentTimeMillis()

    HttpEngine(String host, int port, StorageEngine storageEngine, QueryEngine queryEngine) {
        this.host = host != null ? host : "0.0.0.0"
        this.port = port
        this.storageEngine = storageEngine
        this.queryEngine = queryEngine
    }

    /**
     * Starts the HTTP service endpoints.
     */
    synchronized void start() {
        if (server != null) return

        server = HttpServer.create(new InetSocketAddress(host, port), 256)
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())

        server.createContext("/health", new HealthHandler())
        server.createContext("/write", new WriteHandler())
        server.createContext("/metrics", new MetricsHandler())
        server.createContext("/query", new QueryHandler())

        server.start()
    }

    /**
     * Stops the HTTP service gracefully.
     */
    synchronized void stop() {
        if (server != null) {
            server.stop(1)
            server = null
        }
    }

    int getPort() {
        server != null ? server.getAddress().getPort() : port
    }

    // --- Endpoint Handlers ---

    private class HealthHandler implements HttpHandler {
        @Override
        void handle(HttpExchange exchange) throws IOException {
            long uptime = System.currentTimeMillis() - startupTimestamp
            int segCount = 0
            if (storageEngine != null) {
                StorageStats stats = storageEngine.stats
                segCount = stats != null ? stats.activeSegmentsCount : 0
            }
            String json = "{\"status\":\"UP\",\"uptimeMs\":${uptime},\"segments\":${segCount}}"
            sendResponse(exchange, 200, "application/json", json)
        }
    }

    private class WriteHandler implements HttpHandler {
        @Override
        void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed")
                return
            }

            InputStream is = exchange.getRequestBody()
            ByteArrayOutputStream baos = new ByteArrayOutputStream()
            byte[] buf = new byte[4096]
            int r
            while ((r = is.read(buf)) != -1) {
                baos.write(buf, 0, r)
            }
            String body = baos.toString(StandardCharsets.UTF_8.name())

            try {
                List<LineProtocolParser.ParsedMetric> parsed = LineProtocolParser.parseBatch(body)
                int written = 0
                if (storageEngine != null) {
                    for (LineProtocolParser.ParsedMetric m : parsed) {
                        storageEngine.write(m.key, m.point)
                        written++
                    }
                }
                sendResponse(exchange, 204, "text/plain", "")
            } catch (Exception e) {
                sendResponse(exchange, 400, "text/plain", "Bad Request: " + e.getMessage())
            }
        }
    }

    private class MetricsHandler implements HttpHandler {
        @Override
        void handle(HttpExchange exchange) throws IOException {
            Map<TimeSeriesKey, List<MetricPoint>> exportMap = new HashMap<TimeSeriesKey, List<MetricPoint>>()
            long now = System.currentTimeMillis()

            // Internal engine telemetry
            TimeSeriesKey uptimeKey = TimeSeriesKey.parse("ferrite_uptime_seconds")
            double uptimeSec = (double) (now - startupTimestamp) / 1000.0d
            exportMap.put(uptimeKey, Collections.singletonList(MetricPoint.of(now, uptimeSec)))

            if (storageEngine != null) {
                StorageStats stats = storageEngine.stats
                if (stats != null) {
                    TimeSeriesKey segKey = TimeSeriesKey.parse("ferrite_storage_segments_total")
                    exportMap.put(segKey, Collections.singletonList(MetricPoint.of(now, (double) stats.activeSegmentsCount)))

                    TimeSeriesKey ptsKey = TimeSeriesKey.parse("ferrite_storage_points_ingested_total")
                    exportMap.put(ptsKey, Collections.singletonList(MetricPoint.of(now, (double) stats.totalPointsWritten)))
                }
            }

            String prometheusText = PrometheusParser.exportText(exportMap)
            sendResponse(exchange, 200, "text/plain; version=0.0.4", prometheusText)
        }
    }

    private class QueryHandler implements HttpHandler {
        @Override
        void handle(HttpExchange exchange) throws IOException {
            URI uri = exchange.getRequestURI()
            Map<String, String> queryParams = parseQueryParams(uri.getQuery())
            String metric = queryParams.get("metric")

            if (metric == null || metric.trim().isEmpty()) {
                sendResponse(exchange, 400, "application/json", "{\"error\":\"Missing 'metric' parameter\"}")
                return
            }

            if (queryEngine == null) {
                sendResponse(exchange, 503, "application/json", "{\"error\":\"Query engine unavailable\"}")
                return
            }

            try {
                TimeSeriesKey key = TimeSeriesKey.parse(metric)
                long now = System.currentTimeMillis()
                long from = queryParams.containsKey("from") ? Long.parseLong(queryParams.get("from")) : 0L
                long to = queryParams.containsKey("to") ? Long.parseLong(queryParams.get("to")) : now
                TimeRange range = TimeRange.between(from, to)

                String aggParam = queryParams.get("agg")
                QueryResult res
                if (aggParam != null) {
                    AggregationType agg = AggregationType.valueOf(aggParam.toUpperCase())
                    String windowParam = queryParams.get("window")
                    if (windowParam != null) {
                        long windowMs = TimeRange.parseDuration(windowParam).toMillis()
                        res = queryEngine.executeWindowed(key, range, windowMs, agg)
                    } else {
                        res = queryEngine.executeAggregate(key, range, agg)
                    }
                } else {
                    res = queryEngine.executeRaw(key, range)
                }

                StringBuilder sb = new StringBuilder()
                sb.append("{\"metric\":\"").append(metric).append("\",\"scanned\":").append(res.samplesScanned)
                        .append(",\"executionTimeMs\":").append(res.executionTimeMs).append(",\"points\":[")
                List<MetricPoint> pts = res.series.get(key)
                if (pts == null && !res.series.isEmpty()) {
                    pts = res.series.values().iterator().next()
                }
                if (pts != null) {
                    for (int i = 0; i < pts.size(); i++) {
                        MetricPoint pt = pts.get(i)
                        if (i > 0) sb.append(",")
                        sb.append("{\"timestamp\":").append(pt.timestamp).append(",\"value\":").append(pt.value).append("}")
                    }
                }
                sb.append("]}")
                sendResponse(exchange, 200, "application/json", sb.toString())
            } catch (Exception e) {
                sendResponse(exchange, 500, "application/json", "{\"error\":\"" + e.getMessage() + "\"}")
            }
        }
    }

    private static Map<String, String> parseQueryParams(String query) {
        if (query == null || query.isEmpty()) return Collections.<String, String>emptyMap()
        Map<String, String> map = new LinkedHashMap<String, String>()
        for (String param : query.split("&")) {
            int eq = param.indexOf('=')
            if (eq > 0) {
                map.put(param.substring(0, eq), param.substring(eq + 1))
            }
        }
        map
    }

    private static void sendResponse(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders().set("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.length > 0 ? bytes.length : -1L)
        if (bytes.length > 0) {
            OutputStream os = exchange.getResponseBody()
            os.write(bytes)
            os.close()
        }
        exchange.close()
    }
}
