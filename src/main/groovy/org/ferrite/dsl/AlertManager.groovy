package org.ferrite.dsl

import groovy.transform.CompileStatic
import org.ferrite.common.MetricPoint

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Registry and evaluation coordinator for active alerting rules.
 */
@CompileStatic
class AlertManager {

    private final List<AlertRule> rules = new CopyOnWriteArrayList<AlertRule>()
    private final List<AlertRule.AlertEvent> activeIncidents = new CopyOnWriteArrayList<AlertRule.AlertEvent>()

    /**
     * Registers an alert rule.
     */
    void registerRule(AlertRule rule) {
        if (rule != null && !rules.contains(rule)) {
            rules.add(rule)
        }
    }

    /**
     * Removes an alert rule by name.
     */
    void removeRule(String ruleName) {
        rules.removeIf { AlertRule r -> r.name.equals(ruleName) }
    }

    /**
     * Evaluates a single metric point against all registered rules.
     * Returns any alert events triggered by the point.
     */
    List<AlertRule.AlertEvent> evaluate(MetricPoint point) {
        if (point == null || rules.isEmpty()) {
            return Collections.emptyList()
        }

        List<AlertRule.AlertEvent> events = new ArrayList<AlertRule.AlertEvent>()
        for (AlertRule rule : rules) {
            AlertRule.AlertEvent event = rule.evaluate(point)
            if (event != null) {
                events.add(event)
                if (event.state == AlertRule.State.FIRING && event.firstFired) {
                    activeIncidents.add(event)
                } else if (event.state == AlertRule.State.OK) {
                    activeIncidents.removeIf { AlertRule.AlertEvent inc -> inc.ruleName.equals(event.ruleName) }
                }
            }
        }
        events
    }

    List<AlertRule> getRules() {
        Collections.unmodifiableList(rules)
    }

    List<AlertRule.AlertEvent> getActiveIncidents() {
        Collections.unmodifiableList(activeIncidents)
    }

    int getRuleCount() {
        rules.size()
    }

    int getActiveIncidentCount() {
        activeIncidents.size()
    }
}
