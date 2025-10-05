package org.ferrite.dsl

import groovy.transform.CompileStatic
import org.ferrite.query.QueryPlan

/**
 * Main entrypoint for Groovy-idiomatic DSL syntax across Ferrite:
 * fluent queries, streaming pipelines, and alert rules.
 */
@CompileStatic
class FerriteDsl {

    /**
     * Constructs an optimized QueryPlan using Groovy closure delegation.
     *
     * Example:
     * <pre>
     * def plan = FerriteDsl.query {
     *     from "cpu.usage"
     *     tag "host", "node-01"
     *     between 1000L, 5000L
     *     min 50.0
     *     every "1m", "avg"
     * }
     * </pre>
     */
    static QueryPlan query(@DelegatesTo(value = QueryBuilder, strategy = Closure.DELEGATE_FIRST) Closure closure) {
        QueryBuilder builder = new QueryBuilder()
        closure.delegate = builder
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        closure.call()
        builder.build()
    }

    /**
     * Constructs a reactive StreamPipeline using Groovy closure delegation.
     */
    static StreamPipeline stream(@DelegatesTo(value = StreamPipeline, strategy = Closure.DELEGATE_FIRST) Closure closure) {
        StreamPipeline.build(closure)
    }

    /**
     * Constructs a dynamic AlertRule using Groovy closure delegation.
     */
    static AlertRule alert(@DelegatesTo(value = AlertRule.Builder, strategy = Closure.DELEGATE_FIRST) Closure closure) {
        AlertRule.build(closure)
    }
}
