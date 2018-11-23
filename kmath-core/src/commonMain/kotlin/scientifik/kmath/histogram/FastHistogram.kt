package scientifik.kmath.histogram

import scientifik.kmath.linear.toVector
import scientifik.kmath.structures.Buffer
import scientifik.kmath.structures.ListBuffer
import scientifik.kmath.structures.NDStructure
import scientifik.kmath.structures.ndStructure
import kotlin.math.floor

typealias RealPoint = Point<Double>

private operator fun RealPoint.minus(other: RealPoint) = ListBuffer((0 until size).map { get(it) - other[it] })

private inline fun <T> Buffer<out Double>.mapIndexed(crossinline mapper: (Int, Double) -> T): Sequence<T> = (0 until size).asSequence().map { mapper(it, get(it)) }


class MultivariateBin(override val center: RealPoint, val sizes: RealPoint, var counter: Long = 0) : Bin<Double> {
    init {
        if (center.size != sizes.size) error("Dimension mismatch in bin creation. Expected ${center.size}, but found ${sizes.size}")
    }

    override fun contains(vector: Buffer<out Double>): Boolean {
        if (vector.size != center.size) error("Dimension mismatch for input vector. Expected ${center.size}, but found ${vector.size}")
        return vector.mapIndexed { i, value -> value in (center[i] - sizes[i] / 2)..(center[i] + sizes[i] / 2) }.all { it }
    }

    override val value get() = counter
    internal operator fun inc() = this.also { counter++ }

    override val dimension: Int get() = center.size
}

/**
 * Uniform multivariate histogram with fixed borders. Based on NDStructure implementation with complexity of m for bin search, where m is the number of dimensions.
 * The histogram is optimized for speed, but have large size in memory
 */
class FastHistogram(
        private val lower: RealPoint,
        private val upper: RealPoint,
        private val binNums: IntArray = IntArray(lower.size) { 20 }
) : MutableHistogram<Double, MultivariateBin> {

    init {
        // argument checks
        if (lower.size != upper.size) error("Dimension mismatch in histogram lower and upper limits.")
        if (lower.size != binNums.size) error("Dimension mismatch in bin count.")
        if ((upper - lower).asSequence().any { it <= 0 }) error("Range for one of axis is not strictly positive")
    }


    override val dimension: Int get() = lower.size

    //TODO optimize binSize performance if needed
    private val binSize: RealPoint = ListBuffer((upper - lower).mapIndexed { index, value -> value / binNums[index] }.toList())

    private val bins: NDStructure<MultivariateBin> by lazy {
        val actualSizes = IntArray(binNums.size) { binNums[it] + 2 }
        ndStructure(actualSizes) { indexArray ->
            val center = ListBuffer(
                    indexArray.mapIndexed { axis, index ->
                        when (index) {
                            0 -> Double.NEGATIVE_INFINITY
                            actualSizes[axis] - 1 -> Double.POSITIVE_INFINITY
                            else -> lower[axis] + (index.toDouble() - 0.5) * binSize[axis]
                        }
                    }
            )
            MultivariateBin(center, binSize)
        }
    }

    /**
     * Get internal [NDStructure] bin index for given axis
     */
    private fun getIndex(axis: Int, value: Double): Int {
        return when {
            value >= upper[axis] -> binNums[axis] + 1 // overflow
            value < lower[axis] -> 0 // underflow
            else -> floor((value - lower[axis]) / binSize[axis]).toInt() + 1
        }
    }


    override fun get(point: Buffer<out Double>): MultivariateBin? {
        val index = IntArray(dimension) { getIndex(it, point[it]) }
        return bins[index]
    }

    override fun put(point: Buffer<out Double>, weight: Double) {
        if (weight != 1.0) TODO("Implement weighting")
        this[point]?.inc() ?: error("Could not find appropriate bin (should not be possible)")
    }

    override fun iterator(): Iterator<MultivariateBin> = bins.asSequence().map { it.second }.iterator()

    /**
     * Convert this histogram into NDStructure containing bin values but not bin descriptions
     */
    fun asND(): NDStructure<Number> {
        return ndStructure(this.bins.shape) { bins[it].value }
    }

//    /**
//     * Create a phantom lightweight immutable copy of this histogram
//     */
//    fun asPhantom(): PhantomHistogram<Double> {
//        val center =
//        val binTemplates = bins.associate { (index, bin) -> BinTemplate<Double>(bin.center, bin.sizes) to index }
//        return PhantomHistogram(binTemplates, asND())
//    }

    companion object {

        /**
         * Use it like
         * ```
         *FastHistogram.fromRanges(
         *  (-1.0..1.0),
         *  (-1.0..1.0)
         *)
         *```
         */
        fun fromRanges(vararg ranges: ClosedFloatingPointRange<Double>): FastHistogram {
            return FastHistogram(ranges.map { it.start }.toVector(), ranges.map { it.endInclusive }.toVector())
        }

        /**
         * Use it like
         * ```
         *FastHistogram.fromRanges(
         *  (-1.0..1.0) to 50,
         *  (-1.0..1.0) to 32
         *)
         *```
         */
        fun fromRanges(vararg ranges: Pair<ClosedFloatingPointRange<Double>, Int>): FastHistogram {
            return FastHistogram(
                    ranges.map { it.first.start }.toVector(),
                    ranges.map { it.first.endInclusive }.toVector(),
                    ranges.map { it.second }.toIntArray()
            )
        }
    }

}