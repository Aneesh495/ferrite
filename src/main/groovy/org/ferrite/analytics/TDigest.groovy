package org.ferrite.analytics

import groovy.transform.CompileStatic

import java.util.Arrays

/**
 * Online streaming T-Digest algorithm for extreme percentile estimation (p50, p95, p99, p99.9).
 * Maintains sub-linear memory bounds and constant-time sample ingestion.
 */
@CompileStatic
class TDigest implements Serializable {
    private static final long serialVersionUID = 1L

    static class Centroid implements Comparable<Centroid>, Serializable {
        private static final long serialVersionUID = 1L
        double mean
        double weight

        Centroid(double mean, double weight) {
            this.mean = mean
            this.weight = weight
        }

        void add(double x, double w) {
            this.weight += w
            this.mean += w * (x - this.mean) / this.weight
        }

        @Override
        int compareTo(Centroid o) {
            Double.compare(this.mean, o.mean)
        }
    }

    private final double compression
    private final List<Centroid> centroids
    private double totalWeight
    private final double[] tempValues
    private final double[] tempWeights
    private int tempCount

    TDigest() {
        this(100.0d)
    }

    TDigest(double compression) {
        if (compression <= 0.0d) {
            throw new IllegalArgumentException("Compression factor must be positive: ${compression}")
        }
        this.compression = compression
        this.centroids = new ArrayList<Centroid>()
        this.totalWeight = 0.0d
        int tempCapacity = (int) (compression * 5)
        this.tempValues = new double[tempCapacity]
        this.tempWeights = new double[tempCapacity]
        this.tempCount = 0
    }

    void add(double value) {
        add(value, 1.0d)
    }

    void add(double value, double weight) {
        if (Double.isNaN(value) || weight <= 0.0d) return
        tempValues[tempCount] = value
        tempWeights[tempCount] = weight
        tempCount++
        totalWeight += weight

        if (tempCount >= tempValues.length) {
            compress()
        }
    }

    void compress() {
        if (tempCount == 0 && centroids.isEmpty()) return

        List<Centroid> all = new ArrayList<Centroid>(centroids.size() + tempCount)
        all.addAll(centroids)
        for (int i = 0; i < tempCount; i++) {
            all.add(new Centroid(tempValues[i], tempWeights[i]))
        }
        Collections.sort(all)

        centroids.clear()
        tempCount = 0

        if (all.isEmpty()) return

        Centroid cur = all.get(0)
        double weightSoFar = 0.0d

        for (int i = 1; i < all.size(); i++) {
            Centroid next = all.get(i)
            double proposedWeight = cur.weight + next.weight
            double q0 = weightSoFar / totalWeight
            double q1 = (weightSoFar + proposedWeight) / totalWeight

            double minQ = Math.min((double) (q0 * (1.0d - q0)), (double) (q1 * (1.0d - q1)))
            double limit = (4.0d * totalWeight * minQ) / compression
            if (limit < 1.0d) limit = 1.0d

            if (proposedWeight <= limit) {
                cur.add(next.mean, next.weight)
            } else {
                centroids.add(cur)
                weightSoFar += cur.weight
                cur = next
            }
        }
        centroids.add(cur)
    }

    double quantile(double q) {
        if (q < 0.0d || q > 1.0d) {
            throw new IllegalArgumentException("Percentile fraction q must be between 0 and 1: ${q}")
        }
        if (tempCount > 0) {
            compress()
        }
        if (centroids.isEmpty()) {
            return Double.NaN
        }
        if (centroids.size() == 1) {
            return centroids.get(0).mean
        }

        double targetWeight = q * totalWeight
        if (targetWeight <= 0.0d) {
            return centroids.get(0).mean
        }
        if (targetWeight >= totalWeight) {
            return centroids.get(centroids.size() - 1).mean
        }

        double currentWeight = 0.0d
        for (int i = 0; i < centroids.size(); i++) {
            Centroid c = centroids.get(i)
            double halfWeight = c.weight / 2.0d
            if (currentWeight + halfWeight >= targetWeight) {
                if (i == 0) return c.mean
                Centroid prev = centroids.get(i - 1)
                double prevHalf = prev.weight / 2.0d
                double w = targetWeight - (currentWeight - prevHalf)
                double span = halfWeight + prevHalf
                double t = span > 0.0d ? (double) (w / span) : 0.0d
                return prev.mean + t * (c.mean - prev.mean)
            }
            currentWeight += c.weight
        }

        centroids.get(centroids.size() - 1).mean
    }

    double getTotalWeight() {
        totalWeight
    }

    int centroidCount() {
        centroids.size()
    }
}
