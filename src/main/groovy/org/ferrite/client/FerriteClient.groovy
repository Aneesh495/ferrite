package org.ferrite.client

import groovy.transform.CompileStatic
import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeSeriesKey

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Native Groovy/JVM asynchronous client SDK for Ferrite telemetry clusters.
 * Supports connection multiplexing, async ingestion, and query execution.
 */
@CompileStatic
class FerriteClient implements AutoCloseable {

    private final String baseUrl
    private final HttpClient httpClient

    FerriteClient() {
        this("http://127.0.0.1:8086")
    }

    FerriteClient(String host, int port) {
        this("http://${host}:${port}")
    }

    FerriteClient(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Base URL cannot be empty")
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build()
    }

    /**
     * Ingests a raw InfluxDB line protocol payload asynchronously.
     */
    CompletableFuture<Void> write(String lineProtocol) {
        if (lineProtocol == null || lineProtocol.trim().isEmpty()) {
            return CompletableFuture.completedFuture(null)
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl}/write"))
                .header("Content-Type", "text/plain")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(lineProtocol, StandardCharsets.UTF_8))
                .build()

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenAccept { HttpResponse<Void> resp ->
                    if (resp.statusCode() >= 400) {
                        throw new RuntimeException("Write failed with HTTP status: ${resp.statusCode()}")
                    }
                }
    }

    /**
     * Ingests a single metric point asynchronously.
     */
    CompletableFuture<Void> write(TimeSeriesKey key, MetricPoint point) {
        String line = "${key.metricName}"
        if (!key.tags.isEmpty()) {
            StringBuilder sb = new StringBuilder()
            for (int i = 0; i < key.tags.size(); i++) {
                sb.append(",").append(key.tags.getKeyAt(i)).append("=").append(key.tags.getValueAt(i))
            }
            line += sb.toString()
        }
        line += " value=${point.value} ${point.timestamp * 1_000_000L}\n"
        write(line)
    }

    /**
     * Queries time-series data asynchronously.
     */
    CompletableFuture<String> query(String metric, long fromMs = 0L, long toMs = System.currentTimeMillis()) {
        String uriStr = "${baseUrl}/query?metric=${metric}&from=${fromMs}&to=${toMs}"
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uriStr))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply { HttpResponse<String> resp ->
                    if (resp.statusCode() >= 400) {
                        throw new RuntimeException("Query failed (${resp.statusCode()}): ${resp.body()}")
                    }
                    resp.body()
                }
    }

    /**
     * Queries windowed aggregate rollups asynchronously.
     */
    CompletableFuture<String> queryWindowed(String metric, String windowDuration, String aggregate = "avg") {
        String uriStr = "${baseUrl}/query?metric=${metric}&window=${windowDuration}&agg=${aggregate}"
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uriStr))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply { HttpResponse<String> resp ->
                    if (resp.statusCode() >= 400) {
                        throw new RuntimeException("Query failed (${resp.statusCode()}): ${resp.body()}")
                    }
                    resp.body()
                }
    }

    /**
     * Queries node health status.
     */
    CompletableFuture<String> health() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl}/health"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build()

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply { HttpResponse<String> resp -> resp.body() }
    }

    /**
     * Scrapes Prometheus metrics exposition.
     */
    CompletableFuture<String> scrapeMetrics() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl}/metrics"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build()

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply { HttpResponse<String> resp -> resp.body() }
    }

    @Override
    void close() {
        // HttpClient handles lifecycle
    }
}
