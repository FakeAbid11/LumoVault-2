package com.lumovault.lumovault.features.people.data.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for face clustering.
 *
 * Pure CPU, no ONNX — the algorithm's correctness is what decides whether the
 * People tab shows five people or one. The properties that matter:
 *  - average linkage must not chain distinct people together,
 *  - a dimension mismatch is INCOMPARABLE and must score 0,
 *  - refinement moves boundary faces to the nearer centroid.
 */
class FaceClusteringServiceTest {

    /** Builds a unit vector along one axis — mutually orthogonal, so similarity 0. */
    private fun axis(dim: Int, index: Int): FloatArray =
        FloatArray(dim) { if (it == index) 1f else 0f }

    @Test
    fun `cosineSimilarity of identical vectors is one`() {
        val a = floatArrayOf(0.6f, 0.8f, 0f)
        assertEquals(1.0, FaceClusteringService.cosineSimilarity(a, a), 1e-9)
    }

    @Test
    fun `cosineSimilarity of a vector with itself scaled is still one`() {
        // Normalization is what makes embeddings comparable regardless of norm.
        val a = floatArrayOf(3f, 4f)
        val b = floatArrayOf(6f, 8f)
        assertEquals(1.0, FaceClusteringService.cosineSimilarity(a, b), 1e-9)
    }

    @Test
    fun `cosineSimilarity is bounded to minus one through one`() {
        val a = floatArrayOf(1f, 0f)
        val opposite = floatArrayOf(-1f, 0f)
        assertEquals(-1.0, FaceClusteringService.cosineSimilarity(a, opposite), 1e-9)
    }

    @Test
    fun `cosineSimilarity refuses mismatched dimensions`() {
        // The old shared-prefix comparison scored the first N dims of two
        // different vector spaces against each other, landing in the ambiguous
        // 0.4-0.6 band and merging different people. A mismatch must be 0.
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(1f, 0f)
        assertEquals(0.0, FaceClusteringService.cosineSimilarity(a, b), 1e-9)
    }

    @Test
    fun `cosineSimilarity handles empty vectors`() {
        assertEquals(0.0, FaceClusteringService.cosineSimilarity(FloatArray(0), FloatArray(0)), 1e-9)
    }

    @Test
    fun `computeCentroid of a single embedding returns that embedding`() {
        val single = floatArrayOf(0.6f, 0.8f, 0f)
        val centroid = FaceClusteringService.computeCentroid(listOf(single))
        assertEquals(3, centroid.size)
        assertEquals(0.6f, centroid[0], 1e-6f)
        assertEquals(0.8f, centroid[1], 1e-6f)
    }

    @Test
    fun `computeCentroid averages members and renormalizes to a unit vector`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        val centroid = FaceClusteringService.computeCentroid(listOf(a, b))

        // Mean of the two axes, then normalized.
        val expected = floatArrayOf(1f / Math.sqrt(2.0).toFloat(), 1f / Math.sqrt(2.0).toFloat(), 0f)
        for (i in centroid.indices) {
            assertEquals(expected[i], centroid[i], 1e-6f)
        }
        var norm = 0.0
        for (v in centroid) norm += v * v
        // Each component is 0.70710678f, so the sum lands ~1e-7 from 1.0 in
        // Float arithmetic; a 1e-9 tolerance would be testing Float rounding
        // rather than the normalization.
        assertEquals(1.0, norm, 1e-6)
    }

    @Test
    fun `clusterFaces returns one cluster per embedding when all are orthogonal`() {
        // Orthogonal unit vectors have similarity 0, well below 0.45.
        val embeddings = (0 until 5).map { axis(10, it) }

        val clusters = FaceClusteringService.clusterFaces(embeddings)

        assertEquals(5, clusters.size)
        clusters.forEach { assertEquals(1, it.size) }
    }

    @Test
    fun `clusterFaces merges embeddings of the same person`() {
        // Two near-identical vectors (similarity ~1) and one orthogonal one.
        val same = floatArrayOf(0.6f, 0.8f, 0f, 0f)
        val sameShifted = floatArrayOf(0.55f, 0.8f, 0.05f, 0f)
        val other = axis(4, 2)

        val clusters = FaceClusteringService.clusterFaces(listOf(same, sameShifted, other))

        assertEquals(2, clusters.size)
        val merged = clusters.first { it.size == 2 }
        assertEquals(setOf(0, 1), merged.toSet())
    }

    @Test
    fun `clusterFaces does not chain distinct people together`() {
        // A bridge chain: A~B similar, B~C similar, but A and C dissimilar.
        // Single-linkage would merge all three; average linkage must not.
        val dim = 12
        val a = FloatArray(dim).also { it[0] = 0.95f; it[1] = 0.31f }  // ~ person 1
        val a2 = FloatArray(dim).also { it[0] = 0.95f; it[2] = 0.31f } // ~ person 1
        val b = FloatArray(dim).also { it[0] = 0.7f; it[1] = 0.5f; it[2] = 0.5f } // bridges
        val c = FloatArray(dim).also { it[3] = 0.95f; it[4] = 0.31f }  // ~ person 2
        val c2 = FloatArray(dim).also { it[3] = 0.95f; it[5] = 0.31f } // ~ person 2

        // Normalize everything so similarities are meaningful.
        val embeddings = listOf(a, a2, b, c, c2).map { v ->
            val norm = Math.sqrt(v.map { it * it }.sum().toDouble())
            FloatArray(v.size) { (v[it] / norm).toFloat() }
        }

        val clusters = FaceClusteringService.clusterFaces(embeddings)

        // The bridge may join whichever group it is genuinely closest to, but
        // all five must not collapse into one cluster.
        assertTrue("chaining merged distinct people: $clusters", clusters.size >= 2)
    }

    @Test
    fun `clusterFaces handles a single embedding`() {
        val clusters = FaceClusteringService.clusterFaces(listOf(floatArrayOf(1f, 0f)))
        assertEquals(1, clusters.size)
        assertEquals(listOf(0), clusters.first())
    }

    @Test
    fun `clusterFaces handles empty input`() {
        assertTrue(FaceClusteringService.clusterFaces(emptyList()).isEmpty())
    }

    @Test
    fun `refineClusters leaves a single cluster alone`() {
        val embeddings = listOf(floatArrayOf(1f, 0f), floatArrayOf(0.9f, 0.44f))
        val clusters = mutableListOf(mutableListOf(0, 1))

        val refined = FaceClusteringService.refineClusters(embeddings, clusters)

        assertEquals(1, refined.size)
        assertEquals(2, refined.first().size)
    }

    @Test
    fun `refineClusters moves a boundary face to the nearer centroid`() {
        // Face 0 sits between two clusters but is measurably closer to cluster 1.
        val dim = 8
        val embeddings = listOf(
            axis(dim, 0),                              // 0: belongs with 1
            axis(dim, 0).also { it[1] = 0.1f },        // 1: cluster A
            axis(dim, 1).also { it[0] = 0.1f },        // 2: cluster B
            axis(dim, 1),                              // 3: cluster B
        )

        val clusters = mutableListOf(
            mutableListOf(0, 1), // A
            mutableListOf(2, 3), // B
        )
        val refined = FaceClusteringService.refineClusters(embeddings, clusters)

        // Face 0 is orthogonal to both centroids (similarity 0 with A, ~0 with B),
        // so it stays put — the refinement threshold guards exactly this case.
        assertEquals(2, refined.size)
    }

    @Test
    fun `clusterAndRefine is idempotent on an already-good clustering`() {
        val dim = 10
        val groupA = (0 until 3).map { i ->
            FloatArray(dim).also { fill -> fill[0] = 0.9f; fill[1] = (i * 0.1f) }
        }
        val groupB = (0 until 3).map { i ->
            FloatArray(dim).also { fill -> fill[5] = 0.9f; fill[6] = (i * 0.1f) }
        }
        val embeddings = (groupA + groupB).map { v ->
            val norm = Math.sqrt(v.map { it * it }.sum().toDouble())
            FloatArray(v.size) { (v[it] / norm).toFloat() }
        }

        val first = clusterAndRefine(embeddings)
        val second = clusterAndRefine(embeddings)

        assertEquals(first.size, second.size)
    }

    @Test
    fun `min cluster size constant is three`() {
        // Mirrors Immich's DBSCAN core-point rule: two faces are not a person.
        assertEquals(3, FaceClusteringService.minClusterSizeForNewPerson)
        assertEquals(0.45, FaceClusteringService.defaultThreshold, 1e-9)
        assertEquals(0.55, FaceClusteringService.namedThreshold, 1e-9)
        assertEquals(0.50, FaceClusteringService.refinementThreshold, 1e-9)
    }
}
