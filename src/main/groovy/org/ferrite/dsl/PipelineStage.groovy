package org.ferrite.dsl

import groovy.transform.CompileStatic
import org.ferrite.common.MetricPoint

/**
 * Functional processing stage in a streaming telemetry pipeline.
 */
@CompileStatic
interface PipelineStage {

    /**
     * Processes an incoming metric point, returning zero or more output points.
     */
    List<MetricPoint> process(MetricPoint point)

    /**
     * Flushes any buffered state at window close or stream termination.
     */
    List<MetricPoint> flush()
}
