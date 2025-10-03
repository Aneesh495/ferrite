package org.ferrite.dsl

import groovy.transform.CompileStatic
import org.ferrite.analytics.EWMABands
import org.ferrite.common.MetricPoint

import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate

/**
 * Declarative streaming telemetry pipeline DSL in Groovy.
 * Chains arbitrary filter, transform, windowing, anomaly detection,
 * and sink stages with high-throughput stream execution.
 */
@CompileStatic
class StreamPipeline {

    private final List<PipelineStage> stages = new ArrayList<PipelineStage>()
    private long ingestedCount = 0L
    private long emittedCount = 0L

    /**
     * Builder factory method using Groovy closure delegation.
     */
    static StreamPipeline build(@DelegatesTo(value = StreamPipeline, strategy = Closure.DELEGATE_FIRST) Closure closure) {
        StreamPipeline pipeline = new StreamPipeline()
        closure.delegate = pipeline
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        closure.call()
        pipeline
    }

    /**
     * Appends a predicate filter stage.
     */
    StreamPipeline filter(Predicate<MetricPoint> predicate) {
        stages.add(new FilterStage(predicate))
        this
    }

    StreamPipeline filter(@DelegatesTo(MetricPoint) Closure<Boolean> predicateClosure) {
        stages.add(new FilterStage({ MetricPoint pt ->
            predicateClosure.delegate = pt
            predicateClosure.resolveStrategy = Closure.DELEGATE_FIRST
            Boolean res = predicateClosure.call(pt)
            res != null && res.booleanValue()
        } as Predicate<MetricPoint>))
        this
    }

    /**
     * Appends a 1-to-1 transformation mapping stage.
     */
    StreamPipeline map(Function<MetricPoint, MetricPoint> mapper) {
        stages.add(new MapStage(mapper))
        this
    }

    StreamPipeline map(@DelegatesTo(MetricPoint) Closure<MetricPoint> mapperClosure) {
        stages.add(new MapStage({ MetricPoint pt ->
            mapperClosure.delegate = pt
            mapperClosure.resolveStrategy = Closure.DELEGATE_FIRST
            mapperClosure.call(pt)
        } as Function<MetricPoint, MetricPoint>))
        this
    }

    /**
     * Appends a tumbling time window aggregation stage.
     */
    StreamPipeline window(long windowMs, Function<List<MetricPoint>, MetricPoint> aggregator) {
        stages.add(new TumblingWindowStage(windowMs, aggregator))
        this
    }

    /**
     * Appends an online EWMA anomaly detection stage. Points flagged as anomalies
     * are annotated with the "anomaly" tag or passed to an alert handler.
     */
    StreamPipeline detectAnomaly(double numSigmas = 3.0d, Consumer<MetricPoint> anomalyConsumer = null) {
        stages.add(new AnomalyDetectionStage(numSigmas, anomalyConsumer))
        this
    }

    /**
     * Appends a terminal sink consumer.
     */
    StreamPipeline sink(Consumer<MetricPoint> consumer) {
        stages.add(new SinkStage(consumer))
        this
    }

    StreamPipeline sink(Closure consumerClosure) {
        stages.add(new SinkStage({ MetricPoint pt ->
            consumerClosure.call(pt)
        } as Consumer<MetricPoint>))
        this
    }

    /**
     * Ingests a single metric point through the entire pipeline chain.
     */
    synchronized List<MetricPoint> ingest(MetricPoint point) {
        if (point == null) return Collections.emptyList()
        ingestedCount++

        List<MetricPoint> current = Collections.singletonList(point)
        for (PipelineStage stage : stages) {
            List<MetricPoint> next = new ArrayList<MetricPoint>()
            for (MetricPoint pt : current) {
                List<MetricPoint> out = stage.process(pt)
                if (out != null && !out.isEmpty()) {
                    next.addAll(out)
                }
            }
            current = next
            if (current.isEmpty()) {
                break
            }
        }

        emittedCount += current.size()
        current
    }

    /**
     * Flushes buffered state across all pipeline stages.
     */
    synchronized List<MetricPoint> flush() {
        List<MetricPoint> current = new ArrayList<MetricPoint>()
        for (PipelineStage stage : stages) {
            List<MetricPoint> flushed = stage.flush()
            if (flushed != null && !flushed.isEmpty()) {
                current.addAll(flushed)
            }
        }
        emittedCount += current.size()
        current
    }

    long getIngestedCount() { ingestedCount }
    long getEmittedCount() { emittedCount }
    int getStageCount() { stages.size() }

    // --- Built-in Pipeline Stage Implementations ---

    static class FilterStage implements PipelineStage {
        private final Predicate<MetricPoint> predicate

        FilterStage(Predicate<MetricPoint> predicate) {
            this.predicate = predicate
        }

        @Override
        List<MetricPoint> process(MetricPoint point) {
            predicate.test(point) ? Collections.singletonList(point) : Collections.emptyList()
        }

        @Override
        List<MetricPoint> flush() {
            Collections.emptyList()
        }
    }

    static class MapStage implements PipelineStage {
        private final Function<MetricPoint, MetricPoint> mapper

        MapStage(Function<MetricPoint, MetricPoint> mapper) {
            this.mapper = mapper
        }

        @Override
        List<MetricPoint> process(MetricPoint point) {
            MetricPoint mapped = mapper.apply(point)
            mapped != null ? Collections.singletonList(mapped) : Collections.emptyList()
        }

        @Override
        List<MetricPoint> flush() {
            Collections.emptyList()
        }
    }

    static class TumblingWindowStage implements PipelineStage {
        private final long windowMs
        private final Function<List<MetricPoint>, MetricPoint> aggregator
        private final List<MetricPoint> buffer = new ArrayList<MetricPoint>()
        private long currentWindowStart = -1L

        TumblingWindowStage(long windowMs, Function<List<MetricPoint>, MetricPoint> aggregator) {
            this.windowMs = windowMs
            this.aggregator = aggregator
        }

        @Override
        List<MetricPoint> process(MetricPoint point) {
            List<MetricPoint> output = new ArrayList<MetricPoint>()
            long ptWindow = point.timestamp - (point.timestamp % windowMs)

            if (currentWindowStart == -1L) {
                currentWindowStart = ptWindow
            } else if (ptWindow > currentWindowStart) {
                if (!buffer.isEmpty()) {
                    MetricPoint aggregated = aggregator.apply(new ArrayList<MetricPoint>(buffer))
                    if (aggregated != null) output.add(aggregated)
                    buffer.clear()
                }
                currentWindowStart = ptWindow
            }

            buffer.add(point)
            output
        }

        @Override
        List<MetricPoint> flush() {
            if (!buffer.isEmpty()) {
                MetricPoint aggregated = aggregator.apply(new ArrayList<MetricPoint>(buffer))
                buffer.clear()
                currentWindowStart = -1L
                return aggregated != null ? Collections.singletonList(aggregated) : Collections.emptyList()
            }
            Collections.emptyList()
        }
    }

    static class AnomalyDetectionStage implements PipelineStage {
        private final EWMABands bands
        private final Consumer<MetricPoint> anomalyConsumer

        AnomalyDetectionStage(double numSigmas, Consumer<MetricPoint> anomalyConsumer) {
            this.bands = new EWMABands(0.2d, 0.1d, numSigmas)
            this.anomalyConsumer = anomalyConsumer
        }

        @Override
        List<MetricPoint> process(MetricPoint point) {
            boolean isAnomaly = bands.update(point.value)
            if (isAnomaly && anomalyConsumer != null) {
                anomalyConsumer.accept(point)
            }
            Collections.singletonList(point)
        }

        @Override
        List<MetricPoint> flush() {
            Collections.emptyList()
        }
    }

    static class SinkStage implements PipelineStage {
        private final Consumer<MetricPoint> consumer

        SinkStage(Consumer<MetricPoint> consumer) {
            this.consumer = consumer
        }

        @Override
        List<MetricPoint> process(MetricPoint point) {
            consumer.accept(point)
            Collections.singletonList(point)
        }

        @Override
        List<MetricPoint> flush() {
            Collections.emptyList()
        }
    }
}
