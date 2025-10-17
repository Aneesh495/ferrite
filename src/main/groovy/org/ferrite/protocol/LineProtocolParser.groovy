package org.ferrite.protocol

import groovy.transform.CompileStatic
import org.ferrite.common.MetricPoint
import org.ferrite.common.TagSet
import org.ferrite.common.TimeSeriesKey

/**
 * High-performance parser for the InfluxDB Line Protocol exposition syntax.
 * Syntax: measurement[,tag_key=tag_val...] field_key=field_val... [timestamp_nanos]
 */
@CompileStatic
class LineProtocolParser {

    static class ParsedMetric {
        final TimeSeriesKey key
        final MetricPoint point

        ParsedMetric(TimeSeriesKey key, MetricPoint point) {
            this.key = key
            this.point = point
        }
    }

    /**
     * Parses a single line of InfluxDB line protocol into a ParsedMetric.
     * Returns null for comment lines (#) or blank lines.
     */
    static ParsedMetric parseLine(String line) {
        if (line == null) return null
        String trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null
        }

        // Split line by unescaped spaces: [measurement+tags, fields, optional timestamp]
        List<String> parts = splitUnescaped(trimmed, ' ' as char)
        if (parts.size() < 2) {
            throw new IllegalArgumentException("Invalid line protocol format (expected measurement and fields): ${line}")
        }

        String measurementAndTags = parts.get(0)
        String fieldsPart = parts.get(1)
        long timestampMs = System.currentTimeMillis()

        if (parts.size() >= 3) {
            String tsStr = parts.get(2)
            try {
                long rawTs = Long.parseLong(tsStr)
                // Line protocol timestamps are typically nanoseconds (19 digits) or milliseconds (13 digits)
                if (tsStr.length() >= 18) {
                    timestampMs = (long) (rawTs / 1_000_000L)
                } else if (tsStr.length() >= 15) {
                    timestampMs = (long) (rawTs / 1_000L)
                } else {
                    timestampMs = rawTs
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid timestamp in line protocol: ${tsStr}", e)
            }
        }

        // Parse measurement and tags
        List<String> tagTokens = splitUnescaped(measurementAndTags, ',' as char)
        String measurement = unescape(tagTokens.get(0))
        Map<String, String> tagMap = new LinkedHashMap<String, String>()

        for (int i = 1; i < tagTokens.size(); i++) {
            String tagToken = tagTokens.get(i)
            int eqIdx = tagToken.indexOf('=')
            if (eqIdx > 0 && eqIdx < tagToken.length() - 1) {
                String k = unescape(tagToken.substring(0, eqIdx))
                String v = unescape(tagToken.substring(eqIdx + 1))
                tagMap.put(k, v)
            }
        }

        // Parse fields
        List<String> fieldTokens = splitUnescaped(fieldsPart, ',' as char)
        double primaryValue = 0.0d
        Map<String, Object> auxFields = new HashMap<String, Object>()

        for (String fieldToken : fieldTokens) {
            int eqIdx = fieldToken.indexOf('=')
            if (eqIdx > 0 && eqIdx < fieldToken.length() - 1) {
                String fieldKey = unescape(fieldToken.substring(0, eqIdx))
                String rawVal = fieldToken.substring(eqIdx + 1)
                Object parsedVal = parseFieldValue(rawVal)

                if (fieldKey.equals("value") || primaryValue == 0.0d && parsedVal instanceof Number) {
                    primaryValue = ((Number) parsedVal).doubleValue()
                }
                auxFields.put(fieldKey, parsedVal)
            }
        }

        TimeSeriesKey key = TimeSeriesKey.of(measurement, TagSet.of(tagMap))
        MetricPoint point = MetricPoint.of(timestampMs, primaryValue, auxFields)
        new ParsedMetric(key, point)
    }

    /**
     * Parses a multi-line payload containing line protocol metrics.
     */
    static List<ParsedMetric> parseBatch(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList()
        }

        List<ParsedMetric> results = new ArrayList<ParsedMetric>()
        String[] lines = text.split("\r?\n")
        for (String line : lines) {
            ParsedMetric m = parseLine(line)
            if (m != null) {
                results.add(m)
            }
        }
        results
    }

    private static Object parseFieldValue(String val) {
        if (val.endsWith("i")) {
            try {
                return Long.parseLong(val.substring(0, val.length() - 1))
            } catch (NumberFormatException ignored) {}
        }
        if (val.equalsIgnoreCase("t") || val.equalsIgnoreCase("true")) return Boolean.TRUE
        if (val.equalsIgnoreCase("f") || val.equalsIgnoreCase("false")) return Boolean.FALSE
        if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
            return val.substring(1, val.length() - 1)
        }
        try {
            return Double.parseDouble(val)
        } catch (NumberFormatException ignored) {}
        return val
    }

    private static List<String> splitUnescaped(String s, char delimiter) {
        List<String> parts = new ArrayList<String>()
        StringBuilder sb = new StringBuilder()
        boolean escaping = false

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i)
            if (escaping) {
                sb.append(c)
                escaping = false
            } else if (c == ('\\' as char)) {
                escaping = true
            } else if (c == delimiter) {
                parts.add(sb.toString())
                sb.setLength(0)
            } else {
                sb.append(c)
            }
        }
        parts.add(sb.toString())
        parts
    }

    private static String unescape(String s) {
        s.replace("\\,", ",").replace("\\ ", " ").replace("\\=", "=")
    }
}
