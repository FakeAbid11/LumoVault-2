package com.lumovault.lumovault.features.people.data.service

import kotlin.math.sqrt

/**
 * Clusters face embeddings into people groups.
 *
 * Ported from lib/features/people/data/services/face_clustering_service.dart —
 * pure CPU, no ONNX, which makes it directly unit-testable.
 *
 * Uses cosine similarity on the 512-dim embeddings produced by the single
 * accepted embedder (EdgeFace-XS-GAMMA; the vector model tag on every row is
 * what guarantees spaces never mix).
 */
object FaceClusteringService {

    /**
     * Average-linkage clustering threshold. Two clusters merge only when the
     * *mean* cosine similarity of all cross-cluster face pairs stays above this
     * value. This prevents single-linkage "chaining" where a loose chain of
     * pairwise similarities merges distinct people.
     *
     * InsightFace ArcFace 512-dim embeddings produce cosine similarity
     * 0.50-0.80 for the same person across varied lighting/angles, and
     * 0.10-0.35 for different people. 0.45 is the sweet spot: high enough to
     * separate different people, low enough to keep the same person together
     * across lighting/angle variation.
     */
    const val defaultThreshold = 0.45

    /** Threshold for orphan-to-named-person matching (identity is confirmed). */
    const val namedThreshold = 0.55

    /** Threshold for centroid-based reassignment during refinement. */
    const val refinementThreshold = 0.50

    /**
     * Minimum number of faces required to form a new person cluster. Smaller
     * clusters are left unassigned and may be picked up later by a recluster
     * pass if a matching person accumulates enough faces. Mirrors Immich's
     * DBSCAN "core point" rule.
     */
    const val minClusterSizeForNewPerson = 3

    /**
     * Cosine similarity of two embeddings.
     *
     * A dimension mismatch means embeddings from different models or vector
     * spaces (192-d legacy vs 512-d, or two ONNX embedders). The old
     * shared-prefix comparison silently scored their first N dims against each
     * other — landing in the ambiguous 0.4-0.6 band and merging different
     * people. A mismatch is not "dissimilar", it is INCOMPARABLE: refuse with 0.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a.size != b.size) return 0.0

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator == 0.0) 0.0 else dotProduct / denominator
    }

    /**
     * Centroid (mean) embedding of a list, re-normalized to a unit vector.
     *
     * A single embedding is returned as-is — normalizing it is harmless but
     * wastes a pass, and callers may rely on getting the original vector back.
     */
    fun computeCentroid(embeddings: List<FloatArray>): FloatArray {
        if (embeddings.isEmpty()) return FloatArray(0)
        if (embeddings.size == 1) return embeddings.first()

        val dim = embeddings.first().size
        val centroid = DoubleArray(dim)

        for (emb in embeddings) {
            val limit = minOf(dim, emb.size)
            for (i in 0 until limit) {
                centroid[i] += emb[i]
            }
        }

        for (i in 0 until dim) {
            centroid[i] /= embeddings.size
        }

        // Re-normalize to a unit vector so the centroid stays comparable to
        // the (already unit) member embeddings.
        var norm = 0.0
        for (v in centroid) norm += v * v
        norm = sqrt(norm)
        if (norm > 0) {
            for (i in 0 until dim) centroid[i] /= norm
        }

        return FloatArray(dim) { centroid[it].toFloat() }
    }

    /**
     * Average-linkage agglomerative clustering.
     *
     * Unlike single-linkage (Union-Find), this prevents "chaining" where a
     * loose chain of pairwise similarities merges distinct people. Two clusters
     * merge only when the *mean* cross-cluster similarity stays above
     * [defaultThreshold].
     *
     * Average linkage is tracked as a running sum + pair count per cluster pair,
     * so a merge costs O(n) to fold in rather than re-walking every
     * cross-cluster face pair — that re-walk is what made large batches
     * unusably slow in the first version.
     *
     * Returns the clusters as lists of original embedding indices.
     */
    fun clusterFaces(embeddings: List<FloatArray>): List<List<Int>> {
        val n = embeddings.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(listOf(0))

        val sum = Array(n) { DoubleArray(n) }
        val count = Array(n) { IntArray(n) { 1 } }
        for (i in 0 until n) {
            for (j in (i + 1) until n) {
                val s = cosineSimilarity(embeddings[i], embeddings[j])
                sum[i][j] = s
                sum[j][i] = s
            }
        }

        // Each cluster is a list of face indices; start with N singletons.
        val clusters = Array(n) { mutableListOf(it) }
        // Track which cluster index is still alive (false = merged away).
        val alive = BooleanArray(n) { true }

        while (true) {
            var bestI = -1
            var bestJ = -1
            var bestAvg = 0.0

            for (i in 0 until n) {
                if (!alive[i]) continue
                for (j in (i + 1) until n) {
                    if (!alive[j]) continue
                    val pairs = count[i][j]
                    val avg = if (pairs > 0) sum[i][j] / pairs else 0.0
                    if (avg > bestAvg) {
                        bestAvg = avg
                        bestI = i
                        bestJ = j
                    }
                }
            }

            // Stop if the best merge would drop below threshold.
            if (bestI < 0 || bestAvg < defaultThreshold) break

            // Merge cluster bestJ into bestI and fold its similarity sums in.
            clusters[bestI].addAll(clusters[bestJ])
            alive[bestJ] = false
            for (k in 0 until n) {
                if (!alive[k] || k == bestI) continue
                sum[bestI][k] += sum[bestJ][k]
                sum[k][bestI] = sum[bestI][k]
                count[bestI][k] += count[bestJ][k]
                count[k][bestI] = count[bestI][k]
            }
        }

        return (0 until n).filter { alive[it] }.map { clusters[it].toList() }
    }

    /**
     * Refines clusters by reassigning boundary faces to the nearest centroid.
     *
     * After initial clustering some faces sit between two clusters; this moves
     * each to the centroid it is closest to, but only when that similarity
     * exceeds [refinementThreshold] — a lower bar would let a near-orthogonal
     * vector drag a face into an unrelated person.
     *
     * Mutates [clusters] in place and returns it, matching the original.
     */
    fun refineClusters(embeddings: List<FloatArray>, clusters: MutableList<MutableList<Int>>): List<List<Int>> {
        if (clusters.size <= 1) return clusters

        val centroids = HashMap<Int, FloatArray>()
        for (c in clusters.indices) {
            centroids[c] = computeCentroid(clusters[c].map { embeddings[it] })
        }

        val faceToCluster = HashMap<Int, Int>()
        for (c in clusters.indices) {
            for (i in clusters[c]) {
                faceToCluster[i] = c
            }
        }

        // Reassign faces that are closer to another centroid.
        val reassignments = HashMap<Int, Int>()
        for (i in embeddings.indices) {
            val currentCluster = faceToCluster[i] ?: 0
            var bestCluster = currentCluster
            var bestSimilarity = 0.0

            for (c in clusters.indices) {
                val sim = cosineSimilarity(embeddings[i], centroids.getValue(c))
                if (sim > bestSimilarity) {
                    bestSimilarity = sim
                    bestCluster = c
                }
            }

            if (bestCluster != currentCluster && bestSimilarity >= refinementThreshold) {
                reassignments[i] = bestCluster
            }
        }

        if (reassignments.isNotEmpty()) {
            for ((faceIdx, newCluster) in reassignments) {
                val oldCluster = faceToCluster.getValue(faceIdx)
                clusters[oldCluster].remove(faceIdx)
                clusters[newCluster].add(faceIdx)
            }
            clusters.removeAll { it.isEmpty() }
        }

        return clusters
    }
}

/**
 * Runs clustering followed by refinement.
 *
 * Kept top-level so a coroutine can hand the whole batch to one call —
 * clustering is pure CPU work and would jank the UI if it ran on the main
 * dispatcher while a scan is in flight.
 */
fun clusterAndRefine(embeddings: List<FloatArray>): List<List<Int>> {
    val clusters = FaceClusteringService.clusterFaces(embeddings)
        .mapTo(mutableListOf()) { it.toMutableList() }
    return FaceClusteringService.refineClusters(embeddings, clusters)
}
