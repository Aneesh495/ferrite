package org.ferrite.protocol

import groovy.transform.CompileStatic
import org.ferrite.common.MetricPoint
import org.ferrite.common.TagSet
import org.ferrite.common.TimeSeriesKey

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Parser and exporter for Prometheus Text Exposition Format (version 0.0.4).
 * Parses scraped Prometheus metrics and serializes internal time-series into
 * Prometheus-compliant exposition format for Grafana and Prometheus scraping.
 */
@CompileStatic
class PrometheusParser {

    private static final Pattern METRIC_LINE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{(.*)\\})?\\s+([+-]?(?:\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|NaN|\\+Inf|-Inf))(?:\\s+(\\d+))?\$")

    static class ScrapedSample {
        final TimeSeriesKey key
        final MetricPoint point

        ScrapedSample(TimeSeriesKey key, MetricPoint point) {
            this.key = key
            this.point = point
        }
    }

    /**
     * Parses a Prometheus text exposition payload into a list of ScrapedSamples.
     */
    static List<ScrapedSample> parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList()
        }

        List<ScrapedSample> samples = new ArrayList<ScrapedSample>()
        String[] lines = text.split("\r?\n")
        long now = System.currentTimeMillis()

        for (String line : lines) {
            String trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue
            }

            Matcher matcher = METRIC_LINE.matcher(trimmed)
            if (!matcher.matches()) {
                continue
            }

            String metricName = matcher.group(1)
            String labelString = matcher.group(2)
            String valueStr = matcher.group(3)
            String timestampStr = matcher.group(4)

            double val
            if (valueStr.equals("NaN")) {
                val = Double.NaN
            } else if (valueStr.equals("+Inf") || valueStr.equals("Inf")) {
                val = Double.POSITIVE_INFINITY
            } else if (valueStr.equals("-Inf")) {
                val = Double.NEGATIVE_INFINITY
            } else {
                val = Double.parseDouble(valueStr)
            }

            long ts = now
            if (timestampStr != null && !timestampStr.isEmpty()) {
                ts = Long.parseLong(timestampStr)
            }

            Map<String, String> tags = parseLabels(labelString)
            TimeSeriesKey key = TimeSeriesKey.of(metricName, TagSet.of(tags))
            samples.add(new ScrapedSample(key, MetricPoint.of(ts, val)))
        }

        samples
    }

    /**
     * Serializes time series key and metric points into Prometheus text format.
     */
    static String exportText(Map<TimeSeriesKey, List<MetricPoint>> seriesData) {
        if (seriesData == null || seriesData.isEmpty()) {
            return ""
        }

        StringBuilder sb = new StringBuilder()
        Set<String> declaredTypes = new HashSet<String>()

        for (Map.Entry<TimeSeriesKey, List<MetricPoint>> entry : seriesData.entrySet()) {
            TimeSeriesKey key = entry.key
            List<MetricPoint> points = entry.value
            if (points == null || points.isEmpty()) continue

            if (declaredTypes.add(key.metricName)) {
                sb.append("# TYPE ").append(key.metricName).append(" gauge\n")
            }

            String labelStr = formatLabels(key.tags)
            for (MetricPoint pt : points) {
                sb.append(key.metricName)
                if (!labelStr.isEmpty()) {
                    sb.append("{").append(labelStr).append("}")
                }
                sb.append(" ").append(formatDouble(pt.value))
                sb.append(" ").append(pt.timestamp).append("\n")
            }
        }

        sb.toString()
    }

    private static Map<String, String> parseLabels(String labelString) {
        if (labelString == null || labelString.trim().isEmpty()) {
            return Collections.<String, String>emptyMap()
        }

        Map<String, String> map = new LinkedHashMap<String, String>()
        int i = 0
        int n = labelString.length()

        while (i < n) {
            // Skip commas and whitespace
            while (i < n && (labelString.charAt(i) == (',' as char) || Character.isWhitespace(labelString.charAt(i)))) {
                i++
            }
            if (i >= n) break

            int eq = labelString.indexOf('=', i)
            if (eq < 0) break
            String k = labelString.substring(i, eq).trim()

            int qStart = labelString.indexOf('"', eq + 1)
            if (qStart < 0) break
            int qEnd = qStart + 1
            boolean escaping = false
            StringBuilder val = new StringBuilder()

            while (qEnd < n) {
                char c = labelString.charAt(qEnd)
                if (escaping) {
                    val.append(c)
                    escaping = false
                } else if (c == ('\\' as char)) {
                    escaping = true
                } else if (c == ('"' as char)) {
                    break
                } else {
                    val.append(c)
                }
                qEnd++
            }

            map.put(k, val.toString())
            i = qEnd + 1
        }

        map
    }

    private static String formatLabels(TagSet tags) {
        if (tags == null || tags.isEmpty()) return ""
        StringBuilder sb = new StringBuilder()
        int n = tags.size()
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",")
            sb.append(tags.getKeyAt(i)).append("=\"").append(escapeLabelValue(tags.getValueAt(i))).append("\"")
        }
        sb.toString()
    }

    private static String escapeLabelValue(String s) {
        if (s == null) return ""
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    }

    private static String formatDouble(double v) {
        if (Double.isNaN(v)) return "NaN"
        if (v == Double.POSITIVE_INFINITY) return "+Inf"
        if (v == Double.NEGATIVE_INFINITY) return "-Inf"
        if (v == (long) v) {
            return String.valueOf((long) v)
        }
        String.valueOf(v)
    }
}
