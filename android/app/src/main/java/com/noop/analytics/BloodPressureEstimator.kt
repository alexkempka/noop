package com.noop.analytics

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A personal, cuff-calibrated blood-pressure ESTIMATE from the night's signals. Android-only addition of
 * this fork (no Swift twin). Pure and deterministic, so the JVM suite covers every rule without a strap.
 *
 * Modelled on WHOOP's "Blood Pressure Insights" as WHOOP describes it publicly: one estimate per morning
 * from the pulse waveform during sleep plus heart rate and recovery signals, unlocked by three cuff
 * readings. WHOOP's model is trained on thousands of people; this one is trained on ONE — the wearer —
 * and only on the cuff readings they log. That is why it is built the way it is:
 *
 *  - **Calibration always flows in.** The level of every estimate is the recency-weighted mean of the
 *    wearer's own cuff sessions. One complete session is enough to get a number; every further session
 *    moves it. Nothing here starts from a population value.
 *  - **The night's signals only move the estimate once the cuff says they matter.** Each signal's effect
 *    is learned from the wearer's sessions by ridge regression, which holds every effect at zero until
 *    enough sessions back it. With few sessions the estimate is essentially the calibration level; with
 *    more it follows the nights. That is the "more precise over time" the product owner asked for, and
 *    it cannot invent a relationship the wearer's data does not show.
 *  - **Accuracy is measured, not claimed.** Every complete session is first compared against what the
 *    model would have said WITHOUT it (fitted on the earlier sessions only), and only then learned from.
 *    Those prospective comparisons are the only accuracy this screen states. Until there are
 *    [MIN_COMPARISONS_FOR_SPREAD] of them, the spread is reported as unknown rather than guessed.
 *  - **Stored readings are never rewritten.** Cuff readings stay in the Lab Book as entered; estimates are
 *    recomputed from them on every read.
 *
 * Not a measurement and not a medical device: nothing here classifies a value as high or normal.
 */
object BloodPressureEstimator {

    /** Cuff readings closer than this to the previous one belong to the same session. */
    const val SESSION_WINDOW_SEC = 10L * 60L

    /** Readings that make a session usable for calibration — three in one sitting, as WHOOP asks. */
    const val READINGS_PER_SESSION = 3

    /** After this many days without a new session the screen suggests recalibrating (it keeps estimating). */
    const val RECALIBRATE_AFTER_DAYS = 28L

    /** A session is paired with the latest night that ended at most this long before it. */
    const val MAX_NIGHT_TO_SESSION_SEC = 24L * 3600L

    /** Older sessions count less: half the weight after this many days. Vessels and fit drift. */
    const val RECENCY_HALF_LIFE_DAYS = 90.0

    /**
     * Ridge strength, in units of "sessions". With n sessions a signal's effect is roughly scaled by
     * n / (n + λ): at three sessions it carries about half of what the data suggests, at twenty about
     * 87 %. Small enough to learn, large enough that two sessions cannot produce a steep slope.
     */
    const val RIDGE_LAMBDA = 3.0

    /** Prospective comparisons needed before a spread is stated. */
    const val MIN_COMPARISONS_FOR_SPREAD = 3

    /** Narrowest spread ever shown, so a lucky streak never prints a falsely exact range. */
    const val MIN_SPREAD_MMHG = 3.0

    /** Plausibility guard for a typed cuff value — a slip of the finger shifts every later estimate. */
    val SYSTOLIC_RANGE = 60.0..260.0
    val DIASTOLIC_RANGE = 30.0..160.0

    fun plausible(systolic: Double, diastolic: Double): Boolean =
        systolic in SYSTOLIC_RANGE && diastolic in DIASTOLIC_RANGE && systolic > diastolic

    data class CuffReading(val takenAt: Long, val systolic: Double, val diastolic: Double)

    data class CuffSession(val readings: List<CuffReading>) {
        val start: Long get() = readings.first().takenAt
        val end: Long get() = readings.last().takenAt
        val complete: Boolean get() = readings.size >= READINGS_PER_SESSION
        val systolic: Double get() = median(readings.map { it.systolic })
        val diastolic: Double get() = median(readings.map { it.diastolic })
    }

    /**
     * One night's inputs. Every field may be missing; a missing signal simply contributes nothing.
     * [day] is the wake day (yyyy-MM-dd), [wakeTs] the end of sleep in epoch seconds.
     */
    data class NightFeatures(
        val day: String,
        val wakeTs: Long,
        val restingHr: Double? = null,
        val hrvMs: Double? = null,
        val respRate: Double? = null,
        val pulse: PulseShape? = null,
    )

    /** Pulse-wave shape from the raw optical waveform of one night. */
    data class PulseShape(val riseMs: Double, val riseFraction: Double, val beats: Int)

    /** The signals the model may learn from, in a fixed order. */
    private val FEATURES: List<(NightFeatures) -> Double?> = listOf(
        { it.restingHr },
        { it.hrvMs?.takeIf { v -> v > 0 }?.let { v -> ln(v) } },
        { it.respRate },
        { it.pulse?.riseMs },
        { it.pulse?.riseFraction },
    )

    // MARK: - Sessions

    /** Groups readings into sessions: a reading within [SESSION_WINDOW_SEC] of the previous one continues it. */
    fun sessions(readings: List<CuffReading>): List<CuffSession> {
        if (readings.isEmpty()) return emptyList()
        val sorted = readings.sortedBy { it.takenAt }
        val groups = mutableListOf(mutableListOf(sorted.first()))
        for (r in sorted.drop(1)) {
            if (r.takenAt - groups.last().last().takenAt <= SESSION_WINDOW_SEC) groups.last().add(r)
            else groups.add(mutableListOf(r))
        }
        return groups.map { CuffSession(it) }
    }

    /** The latest night that ended before [session] began, within [MAX_NIGHT_TO_SESSION_SEC]. */
    fun nightFor(session: CuffSession, nights: List<NightFeatures>): NightFeatures? =
        nights.filter { it.wakeTs <= session.start && session.start - it.wakeTs <= MAX_NIGHT_TO_SESSION_SEC }
            .maxByOrNull { it.wakeTs }

    // MARK: - Model

    /** Per-feature scaling taken from ALL nights, so a session's night is placed against the wearer's own range. */
    data class Scaling(val means: List<Double?>, val sds: List<Double?>) {
        fun z(night: NightFeatures?): DoubleArray = DoubleArray(FEATURES.size) { i ->
            val raw = night?.let { FEATURES[i](it) }
            val m = means[i]
            val s = sds[i]
            if (raw == null || m == null || s == null) 0.0 else (raw - m) / s
        }
    }

    fun scaling(nights: List<NightFeatures>): Scaling {
        val means = mutableListOf<Double?>()
        val sds = mutableListOf<Double?>()
        for (f in FEATURES) {
            val v = nights.mapNotNull(f)
            if (v.size < 3) { means += null; sds += null; continue }
            val m = v.average()
            val sd = sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
            if (sd <= 1e-9) { means += null; sds += null } else { means += m; sds += sd }
        }
        return Scaling(means, sds)
    }

    data class Model(
        val sessionsUsed: Int,
        val interceptSys: Double,
        val interceptDia: Double,
        val betaSys: DoubleArray,
        val betaDia: DoubleArray,
        val zMean: DoubleArray,
        val scaling: Scaling,
    ) {
        fun predict(night: NightFeatures?): Pair<Double, Double> {
            val z = scaling.z(night)
            var s = interceptSys
            var d = interceptDia
            for (i in z.indices) {
                s += betaSys[i] * (z[i] - zMean[i])
                d += betaDia[i] * (z[i] - zMean[i])
            }
            return s to d
        }
    }

    /** Fits on the complete sessions given (each with its paired night, possibly null). Null when none. */
    fun fit(training: List<Pair<CuffSession, NightFeatures?>>, scaling: Scaling): Model? {
        val complete = training.filter { it.first.complete }
        if (complete.isEmpty()) return null
        val newest = complete.maxOf { it.first.start }
        val w = complete.map { 0.5.pow(((newest - it.first.start) / 86_400.0) / RECENCY_HALF_LIFE_DAYS) }
        val wSum = w.sum()
        val z = complete.map { scaling.z(it.second) }
        val p = FEATURES.size
        val zMean = DoubleArray(p) { j -> complete.indices.sumOf { k -> w[k] * z[k][j] } / wSum }
        val ys = complete.map { it.first.systolic }
        val yd = complete.map { it.first.diastolic }
        val ysMean = complete.indices.sumOf { w[it] * ys[it] } / wSum
        val ydMean = complete.indices.sumOf { w[it] * yd[it] } / wSum
        val betaS = ridge(z, zMean, ys, ysMean, w)
        val betaD = ridge(z, zMean, yd, ydMean, w)
        return Model(complete.size, ysMean, ydMean, betaS, betaD, zMean, scaling)
    }

    /** Weighted ridge on centred features: (Zcᵀ W Zc + λI) β = Zcᵀ W (y − ȳ). */
    private fun ridge(z: List<DoubleArray>, zMean: DoubleArray, y: List<Double>, yMean: Double, w: List<Double>): DoubleArray {
        val p = zMean.size
        val a = Array(p) { DoubleArray(p) }
        val b = DoubleArray(p)
        for (k in z.indices) {
            for (i in 0 until p) {
                val ci = z[k][i] - zMean[i]
                b[i] += w[k] * ci * (y[k] - yMean)
                for (j in 0 until p) a[i][j] += w[k] * ci * (z[k][j] - zMean[j])
            }
        }
        for (i in 0 until p) a[i][i] += RIDGE_LAMBDA
        return solve(a, b)
    }

    /** Gaussian elimination with partial pivoting; the ridge term keeps the system well posed. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { i -> a[i].copyOf() }
        val v = b.copyOf()
        for (c in 0 until n) {
            var piv = c
            for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[piv][c])) piv = r
            if (abs(m[piv][c]) < 1e-12) continue
            val tmp = m[c]; m[c] = m[piv]; m[piv] = tmp
            val tv = v[c]; v[c] = v[piv]; v[piv] = tv
            for (r in c + 1 until n) {
                val f = m[r][c] / m[c][c]
                for (k in c until n) m[r][k] -= f * m[c][k]
                v[r] -= f * v[c]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            if (abs(m[r][r]) < 1e-12) { x[r] = 0.0; continue }
            var s = v[r]
            for (k in r + 1 until n) s -= m[r][k] * x[k]
            x[r] = s / m[r][r]
        }
        return x
    }

    // MARK: - Assessment

    /** One session judged against the estimate made WITHOUT it. */
    data class Comparison(
        val sessionStart: Long,
        val cuffSys: Double,
        val cuffDia: Double,
        val estSys: Double,
        val estDia: Double,
    ) {
        val errSys: Double get() = estSys - cuffSys
        val errDia: Double get() = estDia - cuffDia
    }

    data class Estimate(
        val day: String,
        val systolic: Double,
        val diastolic: Double,
        /** Half-width of the stated range; null while the accuracy is still unknown. */
        val spreadSys: Double?,
        val spreadDia: Double?,
        val usedPulseWave: Boolean,
    )

    data class Assessment(
        val sessions: List<CuffSession>,
        val completeSessions: Int,
        val openSession: CuffSession?,
        val comparisons: List<Comparison>,
        val rmseSys: Double?,
        val rmseDia: Double?,
        val lastCalibration: Long?,
        val calibrationDue: Boolean,
        val estimates: List<Estimate>,
    )

    /**
     * Everything the screen shows, from the raw readings and nights. [now] in epoch seconds.
     * Estimates are given for every night, newest first, each from the model over ALL complete sessions —
     * a new calibration therefore re-estimates past mornings too, which the screen says.
     */
    fun assess(readings: List<CuffReading>, nights: List<NightFeatures>, now: Long): Assessment {
        val sessions = sessions(readings.filter { plausible(it.systolic, it.diastolic) })
        val complete = sessions.filter { it.complete }
        val scaling = scaling(nights)
        val paired = complete.map { it to nightFor(it, nights) }

        // Prospective: session i is predicted by the model fitted on sessions 0..i-1 only.
        val comparisons = (1 until paired.size).mapNotNull { i ->
            val model = fit(paired.subList(0, i), scaling) ?: return@mapNotNull null
            val (s, d) = model.predict(paired[i].second)
            Comparison(paired[i].first.start, paired[i].first.systolic, paired[i].first.diastolic, s, d)
        }
        val rmseSys = rmse(comparisons.map { it.errSys })
        val rmseDia = rmse(comparisons.map { it.errDia })
        val spreadKnown = comparisons.size >= MIN_COMPARISONS_FOR_SPREAD

        val model = fit(paired, scaling)
        val estimates = if (model == null) emptyList() else nights.sortedByDescending { it.wakeTs }.map { n ->
            val (s, d) = model.predict(n)
            Estimate(
                day = n.day,
                systolic = s,
                diastolic = d,
                spreadSys = if (spreadKnown) max(rmseSys ?: 0.0, MIN_SPREAD_MMHG) else null,
                spreadDia = if (spreadKnown) max(rmseDia ?: 0.0, MIN_SPREAD_MMHG) else null,
                usedPulseWave = n.pulse != null,
            )
        }
        val last = complete.maxOfOrNull { it.start }
        val open = sessions.lastOrNull()?.takeIf { !it.complete && now - it.end <= SESSION_WINDOW_SEC }
        return Assessment(
            sessions = sessions,
            completeSessions = complete.size,
            openSession = open,
            comparisons = comparisons,
            rmseSys = rmseSys,
            rmseDia = rmseDia,
            lastCalibration = last,
            calibrationDue = last != null && now - last > RECALIBRATE_AFTER_DAYS * 86_400L,
            estimates = estimates,
        )
    }

    private fun rmse(errors: List<Double>): Double? =
        if (errors.isEmpty()) null else sqrt(errors.sumOf { it * it } / errors.size)

    internal fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    // MARK: - Pulse-wave shape

    /** Samples per second of the R26 optical window: a base code plus 24 deltas. */
    const val PPG_SAMPLE_RATE = 25

    /** Beats needed before a night's pulse shape is trusted. */
    const val MIN_BEATS = 200

    /**
     * Pulse-wave shape over a night from consecutive one-second optical windows ([seconds] are
     * (epochSecond, absolute samples) pairs, ascending). Only runs of back-to-back seconds of at least ten
     * seconds are used, so a beat is never stitched across a gap.
     *
     * The optical units and even the sign of the signal are not established for this record, so the
     * polarity is chosen from the data: a pulse rises faster than it falls, so the orientation whose
     * median rise takes the smaller share of the beat is taken as upright. Null below [MIN_BEATS].
     */
    fun pulseShape(seconds: List<Pair<Long, List<Long>>>): PulseShape? {
        val runs = mutableListOf<MutableList<Long>>()
        var lastTs: Long? = null
        for ((ts, samples) in seconds) {
            if (samples.size != PPG_SAMPLE_RATE) { lastTs = null; continue }
            if (lastTs == null || ts != lastTs + 1) runs.add(mutableListOf())
            runs.last().addAll(samples)
            lastTs = ts
        }
        val usable = runs.filter { it.size >= PPG_SAMPLE_RATE * 10 }
        if (usable.isEmpty()) return null
        val up = usable.flatMap { r -> beats(detrend(r), DoubleArray(r.size) { r[it].toDouble() }) }
        val down = usable.flatMap { r ->
            val d = detrend(r)
            beats(DoubleArray(d.size) { -d[it] }, DoubleArray(r.size) { -r[it].toDouble() })
        }
        val pick = listOf(up, down).filter { it.size >= MIN_BEATS }
            .minByOrNull { b -> median(b.map { it.second }) } ?: return null
        val msPerSample = 1000.0 / PPG_SAMPLE_RATE
        return PulseShape(
            riseMs = median(pick.map { it.first }) * msPerSample,
            riseFraction = median(pick.map { it.second }),
            beats = pick.size,
        )
    }

    /** Removes the slow baseline with a centred one-second moving mean. */
    private fun detrend(x: List<Long>): DoubleArray {
        val n = x.size
        val half = PPG_SAMPLE_RATE / 2
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + x[i]
        return DoubleArray(n) { i ->
            val lo = max(0, i - half)
            val hi = minOf(n, i + half + 1)
            x[i] - (prefix[hi] - prefix[lo]) / (hi - lo)
        }
    }

    /**
     * Beats as (rise in samples, rise as a share of the beat). Peaks are FOUND on the detrended signal
     * [x] — a local maximum above the baseline at least 0.32 s after the previous one — but foot and peak
     * are MEASURED on the untouched signal [raw]: a one-second moving mean leaves a ripple on a beat that
     * is not exactly one second long, and that ripple shifted the measured rise by two samples.
     * Beats outside 0.32–2 s are dropped, as is any beat whose foot sits on the previous peak.
     */
    private fun beats(x: DoubleArray, raw: DoubleArray): List<Pair<Double, Double>> {
        val minGap = 8
        val maxGap = 50
        val peaks = mutableListOf<Int>()
        for (i in 1 until x.size - 1) {
            if (x[i] > 0 && x[i] > x[i - 1] && x[i] >= x[i + 1]) {
                if (peaks.isEmpty() || i - peaks.last() >= minGap) peaks += i
                else if (x[i] > x[peaks.last()]) peaks[peaks.size - 1] = i
            }
        }
        // Refine each peak to the raw maximum within two samples.
        for (k in peaks.indices) {
            var best = peaks[k]
            for (i in max(0, peaks[k] - 2)..minOf(raw.size - 1, peaks[k] + 2)) if (raw[i] > raw[best]) best = i
            peaks[k] = best
        }
        val out = mutableListOf<Pair<Double, Double>>()
        for (k in 1 until peaks.size) {
            val prev = peaks[k - 1]
            val cur = peaks[k]
            val period = cur - prev
            if (period < minGap || period > maxGap) continue
            var foot = prev + 1
            if (foot >= cur) continue
            for (i in prev + 1 until cur) if (raw[i] < raw[foot]) foot = i
            val rise = cur - foot
            if (rise <= 0 || foot == prev + 1 && raw[foot] >= raw[prev]) continue
            out += rise.toDouble() to rise.toDouble() / period
        }
        return out
    }
}
