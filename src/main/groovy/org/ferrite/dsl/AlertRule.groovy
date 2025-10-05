package org.ferrite.dsl

import groovy.transform.CompileStatic
import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeRange
import org.ferrite.common.TimeSeriesKey

import java.util.function.Consumer
import java.util.function.Predicate

/**
 * Dynamic alerting rule specification and state machine.
 * Tracks threshold evaluations, pending sustained duration windows,
 * and firing transitions.
 */
@CompileStatic
class AlertRule {

    enum Severity {
        INFO, WARNING, CRITICAL
    }

    enum State {
        OK, PENDING, FIRING
    }

    private final String name
    private final String metricName
    private final Map<String, String> tagFilters
    private final Predicate<MetricPoint> condition
    private final long forDurationMs
    private final Severity severity
    private final Consumer<AlertEvent> handler

    private State state = State.OK
    private long firstViolationTimestamp = -1L
    private long lastEvaluatedTimestamp = -1L
    private long firingCount = 0L

    AlertRule(
            String name,
            String metricName,
            Map<String, String> tagFilters,
            Predicate<MetricPoint> condition,
            long forDurationMs,
            Severity severity,
            Consumer<AlertEvent> handler
    ) {
        this.name = name
        this.metricName = metricName
        this.tagFilters = tagFilters != null ? Collections.unmodifiableMap(new LinkedHashMap<String, String>(tagFilters)) : Collections.<String, String>emptyMap()
        this.condition = condition
        this.forDurationMs = forDurationMs
        this.severity = severity != null ? severity : Severity.WARNING
        this.handler = handler
    }

    AlertEvent evaluate(MetricPoint point) {
        evaluate(null, point)
    }

    /**
     * Evaluates an incoming point against the rule. Returns true if rule fired or transitioned.
     */
    synchronized AlertEvent evaluate(TimeSeriesKey key, MetricPoint point) {
        lastEvaluatedTimestamp = point.timestamp

        // Check series key criteria if key is present
        if (key != null) {
            if (metricName != null && !key.metricName.equals(metricName)) {
                return null
            }
            for (Map.Entry<String, String> entry : tagFilters.entrySet()) {
                String val = key.tags.get(entry.key)
                if (!entry.value.equals(val)) {
                    return null
                }
            }
        }

        boolean violated = condition.test(point)

        if (violated) {
            if (state == State.OK) {
                state = State.PENDING
                firstViolationTimestamp = point.timestamp
            }

            long sustained = point.timestamp - firstViolationTimestamp
            if (sustained >= forDurationMs) {
                boolean wasAlreadyFiring = (state == State.FIRING)
                state = State.FIRING
                firingCount++

                AlertEvent event = new AlertEvent(
                        ruleName: name,
                        state: State.FIRING,
                        severity: severity,
                        triggerPoint: point,
                        sustainedDurationMs: sustained,
                        firstFired: !wasAlreadyFiring
                )
                if (handler != null) {
                    handler.accept(event)
                }
                return event
            }
        } else {
            // Condition cleared
            if (state != State.OK) {
                State prevState = state
                state = State.OK
                firstViolationTimestamp = -1L

                if (prevState == State.FIRING) {
                    AlertEvent resolveEvent = new AlertEvent(
                            ruleName: name,
                            state: State.OK,
                            severity: severity,
                            triggerPoint: point,
                            sustainedDurationMs: 0L,
                            firstFired: false
                    )
                    if (handler != null) {
                        handler.accept(resolveEvent)
                    }
                    return resolveEvent
                }
            }
        }

        return null
    }

    String getName() { name }
    String getMetricName() { metricName }
    Severity getSeverity() { severity }
    State getState() { state }
    long getFiringCount() { firingCount }

    static AlertRule build(@DelegatesTo(value = Builder, strategy = Closure.DELEGATE_FIRST) Closure closure) {
        Builder b = new Builder()
        closure.delegate = b
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        closure.call()
        b.build()
    }

    static class AlertEvent {
        String ruleName
        State state
        Severity severity
        MetricPoint triggerPoint
        long sustainedDurationMs
        boolean firstFired
    }

    static class Builder {
        private String name
        private String metric
        private final Map<String, String> tags = new LinkedHashMap<String, String>()
        private Predicate<MetricPoint> predicate
        private long durationMs = 0L
        private Severity severity = Severity.WARNING
        private Consumer<AlertEvent> onAlert

        Builder name(String n) {
            this.name = n
            this
        }

        Builder metric(String m) {
            this.metric = m
            this
        }

        Builder tag(String k, String v) {
            this.tags.put(k, v)
            this
        }

        Builder condition(Predicate<MetricPoint> cond) {
            this.predicate = cond
            this
        }

        Builder condition(@DelegatesTo(MetricPoint) Closure<Boolean> condClosure) {
            this.predicate = { MetricPoint pt ->
                condClosure.delegate = pt
                condClosure.resolveStrategy = Closure.DELEGATE_FIRST
                Boolean r = condClosure.call(pt)
                r != null && r.booleanValue()
            } as Predicate<MetricPoint>
            this
        }

        Builder sustainedFor(String duration) {
            this.durationMs = TimeRange.parseDuration(duration).toMillis()
            this
        }

        Builder sustainedFor(long ms) {
            this.durationMs = ms
            this
        }

        Builder severity(Severity sev) {
            this.severity = sev
            this
        }

        Builder notify(Consumer<AlertEvent> handler) {
            this.onAlert = handler
            this
        }

        Builder notify(Closure handlerClosure) {
            this.onAlert = { AlertEvent event ->
                handlerClosure.call(event)
            } as Consumer<AlertEvent>
            this
        }

        AlertRule build() {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalStateException("AlertRule requires a name")
            }
            if (predicate == null) {
                throw new IllegalStateException("AlertRule requires a condition")
            }
            new AlertRule(name, metric, tags, predicate, durationMs, severity, onAlert)
        }
    }
}
