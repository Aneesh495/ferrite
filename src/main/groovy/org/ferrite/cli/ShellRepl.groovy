package org.ferrite.cli

import groovy.transform.CompileStatic
import org.ferrite.client.FerriteClient

import java.util.Scanner

/**
 * Interactive terminal REPL for ad-hoc querying, telemetry ingestion,
 * and cluster administration in Ferrite.
 */
@CompileStatic
class ShellRepl {

    private final FerriteClient client
    private boolean active = true

    ShellRepl(String host = "127.0.0.1", int port = 8086) {
        this.client = new FerriteClient(host, port)
    }

    void start() {
        printWelcome()
        Scanner scanner = new Scanner(System.in)

        while (active) {
            System.out.print("ferrite> ")
            if (!scanner.hasNextLine()) break
            String line = scanner.nextLine().trim()
            if (line.isEmpty()) continue

            try {
                handleCommand(line)
            } catch (Exception e) {
                System.err.println("[error] " + e.getMessage())
            }
        }
    }

    void handleCommand(String line) {
        String[] parts = line.split("\\s+", 2)
        String cmd = parts[0].toLowerCase()
        String args = parts.length > 1 ? parts[1].trim() : ""

        switch (cmd) {
            case "help":
                printHelp()
                break
            case "health":
            case "status":
                println client.health().get()
                break
            case "write":
                if (args.isEmpty()) {
                    println "Usage: write <line-protocol-payload>"
                } else {
                    client.write(args).get()
                    println "[ok] Ingested successfully."
                }
                break
            case "query":
                if (args.isEmpty()) {
                    println "Usage: query <metric-name> [fromEpochMs] [toEpochMs]"
                } else {
                    String[] qParts = args.split("\\s+")
                    String metric = qParts[0]
                    long from = qParts.length > 1 ? Long.parseLong(qParts[1]) : 0L
                    long to = qParts.length > 2 ? Long.parseLong(qParts[2]) : System.currentTimeMillis()
                    String res = client.query(metric, from, to).get()
                    println res
                }
                break
            case "window":
                String[] wParts = args.split("\\s+")
                if (wParts.length < 3) {
                    println "Usage: window <metric-name> <window-duration> <agg: avg|sum|min|max|count>"
                } else {
                    String res = client.queryWindowed(wParts[0], wParts[1], wParts[2]).get()
                    println res
                }
                break
            case "metrics":
                println client.scrapeMetrics().get()
                break
            case "exit":
            case "quit":
                active = false
                println "Goodbye."
                break
            default:
                println "Unknown command: '${cmd}'. Type 'help' for available commands."
                break
        }
    }

    private void printWelcome() {
        println """
========================================================================
   ______             _ _
  |  ____|           (_) |
  | |__ ___ _ __ _ __ _| |_ ___
  |  __/ _ \\ '__| '__| | __/ _ \\
  | | |  __/ |  | |  | | ||  __/
  |_|  \\___|_|  |_|  |_|\\__\\___|   Interactive Shell

  Connected to Ferrite node. Type 'help' for command manual.
========================================================================
"""
    }

    private void printHelp() {
        println """
Available Commands:
  help                                Show this manual
  status / health                     Show node health and active segments
  write <line_protocol>               Ingest InfluxDB line protocol measurement
  query <metric> [from] [to]          Query raw metric samples across time range
  window <metric> <duration> <agg>    Compute windowed rollup aggregation
  metrics                             Dump Prometheus exposition telemetry
  quit / exit                         Exit interactive shell
"""
    }

    static void main(String[] args) {
        String host = "127.0.0.1"
        int port = 8086
        if (args.length >= 1) host = args[0]
        if (args.length >= 2) port = Integer.parseInt(args[1])

        ShellRepl repl = new ShellRepl(host, port)
        repl.start()
    }
}
