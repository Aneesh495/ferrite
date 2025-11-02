package org.ferrite.integration

import org.ferrite.FerriteServer
import org.ferrite.client.FerriteClient
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

class EndToEndIntegrationSpec extends Specification {

    @Shared
    Path tempDir
    @Shared
    FerriteServer server
    @Shared
    FerriteClient client
    @Shared
    int httpPort
    @Shared
    int reactorPort

    def setupSpec() {
        tempDir = Files.createTempDirectory("ferrite-integration-test-")
        httpPort = 18086
        reactorPort = 19091

        server = new FerriteServer(tempDir, httpPort, reactorPort, "test-node-1", "127.0.0.1")
        server.start()

        client = new FerriteClient("127.0.0.1", httpPort)
    }

    def cleanupSpec() {
        if (server != null) {
            server.stop()
        }
        if (tempDir != null && Files.exists(tempDir)) {
            tempDir.toFile().deleteDir()
        }
    }

    def "Server should report UP status on health endpoint"() {
        when:
        String healthJson = client.health().get()

        then:
        healthJson.contains("\"status\":\"UP\"")
    }

    def "Server should ingest line protocol metrics and query them back over HTTP"() {
        given: "a batch of telemetry points in InfluxDB line protocol"
        long baseTs = 1700000000000L
        String payload = """
cpu_load,host=srv-01,region=us-east value=25.5 ${baseTs * 1_000_000L}
cpu_load,host=srv-01,region=us-east value=35.5 ${(baseTs + 1000L) * 1_000_000L}
cpu_load,host=srv-01,region=us-east value=45.5 ${(baseTs + 2000L) * 1_000_000L}
"""

        when: "ingesting batch via HTTP write"
        client.write(payload).get()

        and: "querying raw metric back"
        String queryResult = client.query("cpu_load", baseTs - 1000L, baseTs + 5000L).get()

        then: "points are returned correctly in JSON format"
        queryResult.contains("\"metric\":\"cpu_load\"")
        queryResult.contains("\"value\":25.5")
        queryResult.contains("\"value\":35.5")
        queryResult.contains("\"value\":45.5")
    }

    def "Server should compute windowed aggregations via HTTP query API"() {
        given: "time series with known values"
        long startTs = 1700000010000L
        String payload = """
mem_free,host=srv-02 value=100.0 ${startTs * 1_000_000L}
mem_free,host=srv-02 value=200.0 ${(startTs + 1000L) * 1_000_000L}
mem_free,host=srv-02 value=300.0 ${(startTs + 2000L) * 1_000_000L}
"""
        client.write(payload).get()

        when: "querying 5-second tumbling window with average aggregation"
        String result = client.queryWindowed("mem_free", "5s", "avg").get()

        then: "average of 100, 200, 300 is 200"
        result.contains("\"metric\":\"mem_free\"")
        result.contains("\"value\":200.0")
    }

    def "Server should export Prometheus metrics exposition"() {
        when:
        String metrics = client.scrapeMetrics().get()

        then:
        metrics.contains("# TYPE")
        metrics.contains("ferrite_uptime_seconds")
    }
}
