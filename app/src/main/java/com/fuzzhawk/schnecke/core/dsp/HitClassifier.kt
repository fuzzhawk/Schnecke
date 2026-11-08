package com.fuzzhawk.schnecke.core.dsp

import com.fuzzhawk.schnecke.core.model.ClassProfile
import com.fuzzhawk.schnecke.core.model.Layer
import com.fuzzhawk.schnecke.core.model.Voice
import kotlin.math.log2
import kotlin.math.sqrt

/**
 * Decides which voice each hit is.
 *
 * Without training, hits are split into two clusters *relative to each other*, so whatever surface
 * you tap, the darker cluster becomes the kick and the brighter one the snare (or, for beatbox, the
 * brighter cluster becomes hi-hats and the darker one percussion). A taught [ClassProfile] replaces
 * that with nearest-centroid matching against your own examples.
 */
object HitClassifier {

    /** Below this centroid gap (in octaves) two clusters are treated as one kind of sound. */
    private const val MIN_OCTAVE_GAP = 0.45
    private const val MIN_SEPARATION = 1.6
    private const val OPEN_HAT_MIN_DECAY_MS = 140.0

    fun classify(layer: Layer, hits: List<HitFeatures>, profile: ClassProfile?): List<Voice> {
        if (hits.isEmpty()) return emptyList()
        if (profile != null && profile.voices.isNotEmpty()) return classifyWithProfile(hits, profile)
        return when (layer) {
            Layer.HANDS -> classifyHands(hits)
            Layer.BEATBOX -> classifyBeatbox(hits)
        }
    }

    private fun classifyHands(hits: List<HitFeatures>): List<Voice> {
        val split = splitByBrightness(hits)
        if (split == null) {
            val median = Stats.median(hits.map { it.centroidHz })
            val v = if (median < 1800) Voice.KICK else Voice.SNARE
            return hits.map { v }
        }
        return split.map { bright -> if (bright) Voice.SNARE else Voice.KICK }
    }

    private fun classifyBeatbox(hits: List<HitFeatures>): List<Voice> {
        val split = splitByBrightness(hits)
            ?: run {
                val median = Stats.median(hits.map { it.centroidHz })
                val allBright = median > 3500
                hits.map { allBright }
            }
        return hits.indices.map { i ->
            when {
                !split[i] -> Voice.PERC
                hits[i].decayMs > OPEN_HAT_MIN_DECAY_MS -> Voice.OPEN_HAT
                else -> Voice.HIHAT
            }
        }
    }

    /**
     * Two-means clustering on standardized timbre. Returns, per hit, whether it is in the brighter
     * cluster, or null when the hits do not form two clearly different groups.
     */
    internal fun splitByBrightness(hits: List<HitFeatures>): List<Boolean>? {
        if (hits.size < 2) return null
        val raw = hits.map { it.timbre() }
        val (mean, std) = meanStd(raw)
        val pts = raw.map { standardize(it, mean, std) }
        val logC = hits.map { log2(it.centroidHz.coerceAtLeast(20.0)) }

        var a = pts[logC.indices.minBy { logC[it] }]
        var b = pts[logC.indices.maxBy { logC[it] }]
        var assign = IntArray(pts.size)
        repeat(50) {
            val next = IntArray(pts.size) { i -> if (dist2(pts[i], a) <= dist2(pts[i], b)) 0 else 1 }
            val same = next.contentEquals(assign)
            assign = next
            val ca = pts.filterIndexed { i, _ -> assign[i] == 0 }
            val cb = pts.filterIndexed { i, _ -> assign[i] == 1 }
            if (ca.isEmpty() || cb.isEmpty()) return null
            a = centroid(ca)
            b = centroid(cb)
            if (same) return@repeat
        }
        val idxA = assign.indices.filter { assign[it] == 0 }
        val idxB = assign.indices.filter { assign[it] == 1 }
        if (idxA.isEmpty() || idxB.isEmpty()) return null
        val octA = idxA.map { logC[it] }.average()
        val octB = idxB.map { logC[it] }.average()
        val within = pts.indices.map { i -> dist2(pts[i], if (assign[i] == 0) a else b) }.average()
        val separation = dist2(a, b) / within.coerceAtLeast(1e-6)
        if (kotlin.math.abs(octA - octB) < MIN_OCTAVE_GAP || separation < MIN_SEPARATION) return null
        val brightCluster = if (octB > octA) 1 else 0
        return assign.map { it == brightCluster }
    }

    private fun classifyWithProfile(hits: List<HitFeatures>, profile: ClassProfile): List<Voice> {
        val mean = profile.mean.toDoubleArray()
        val std = profile.std.toDoubleArray()
        val cents = profile.centroids.map { it.toDoubleArray() }
        return hits.map { h ->
            val p = standardize(h.full(), mean, std)
            val best = cents.indices.minBy { dist2(p, cents[it]) }
            profile.voices[best]
        }
    }

    /** Build a profile from examples of each voice (at least one per voice used). */
    fun train(examples: Map<Voice, List<HitFeatures>>): ClassProfile? {
        val used = examples.filterValues { it.isNotEmpty() }
        if (used.size < 2) return null
        val all = used.values.flatten().map { it.full() }
        val (mean, std) = meanStd(all)
        val voices = used.keys.toList()
        val cents = voices.map { v -> centroid(used.getValue(v).map { standardize(it.full(), mean, std) }) }
        return ClassProfile(
            voices = voices,
            centroids = cents.map { it.toList() },
            mean = mean.toList(),
            std = std.toList(),
        )
    }

    private fun meanStd(rows: List<DoubleArray>): Pair<DoubleArray, DoubleArray> {
        val d = rows.first().size
        val mean = DoubleArray(d) { j -> rows.sumOf { it[j] } / rows.size }
        val std = DoubleArray(d) { j ->
            val v = rows.sumOf { (it[j] - mean[j]) * (it[j] - mean[j]) } / rows.size
            sqrt(v).let { if (it < 1e-9) 1.0 else it }
        }
        return mean to std
    }

    private fun standardize(v: DoubleArray, mean: DoubleArray, std: DoubleArray) =
        DoubleArray(v.size) { (v[it] - mean[it]) / std[it] }

    private fun centroid(rows: List<DoubleArray>): DoubleArray {
        val d = rows.first().size
        return DoubleArray(d) { j -> rows.sumOf { it[j] } / rows.size }
    }

    private fun dist2(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) { val d = a[i] - b[i]; s += d * d }
        return s
    }
}
