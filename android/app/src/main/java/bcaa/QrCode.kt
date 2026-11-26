package bcaa

import kotlin.math.abs

/**
 * An immutable QR Code Model 2 symbol. Use [encodeText], [encodeBinary], or [encodeSegments]
 * to build one from a payload. The constructor accepts packed data codewords before error correction.
 */
class QrCode(
    @JvmField val version: Int,
    @JvmField val errorCorrectionLevel: Ecc,
    dataCodewords: ByteArray,
    mask: Int,
) {
    @JvmField val size: Int
    @JvmField val mask: Int

    private val modules: Array<BooleanArray>
    // Function modules are only needed while constructing the symbol.
    private var functionModules: Array<BooleanArray>

    init {
        require(version in MIN_VERSION..MAX_VERSION) { "Version value out of range" }
        require(mask in -1..7) { "Mask value out of range" }
        size = version * 4 + 17
        modules = Array(size) { BooleanArray(size) }
        functionModules = Array(size) { BooleanArray(size) }

        drawFunctionPatterns()
        drawCodewords(addEccAndInterleave(dataCodewords))

        var selectedMask = mask
        if (selectedMask == -1) {
            var minPenalty = Int.MAX_VALUE
            for (candidate in 0..7) {
                applyMask(candidate)
                drawFormatBits(candidate)
                val penalty = getPenaltyScore()
                if (penalty < minPenalty) {
                    selectedMask = candidate
                    minPenalty = penalty
                }
                applyMask(candidate) // Applying the same XOR mask twice restores the data.
            }
        }
        this.mask = selectedMask
        applyMask(selectedMask)
        drawFormatBits(selectedMask)
        functionModules = emptyArray()
    }

    /** Returns whether the module is dark. Coordinates outside the symbol are light. */
    fun getModule(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && modules[y][x]

    private fun drawFunctionPatterns() {
        for (index in 0 until size) {
            setFunctionModule(6, index, index % 2 == 0)
            setFunctionModule(index, 6, index % 2 == 0)
        }

        drawFinderPattern(3, 3)
        drawFinderPattern(size - 4, 3)
        drawFinderPattern(3, size - 4)

        val positions = getAlignmentPatternPositions()
        for (x in positions.indices) {
            for (y in positions.indices) {
                val finderCorner = (x == 0 && (y == 0 || y == positions.lastIndex)) ||
                    (x == positions.lastIndex && y == 0)
                if (!finderCorner) drawAlignmentPattern(positions[x], positions[y])
            }
        }

        drawFormatBits(0) // Reserve format modules before choosing a mask.
        drawVersion()
    }

    /** Draws both copies of the format bits, including their error correction bits. */
    private fun drawFormatBits(mask: Int) {
        val data = (errorCorrectionLevel.formatBits shl 3) or mask
        var remainder = data
        repeat(10) {
            remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537)
        }
        val bits = ((data shl 10) or remainder) xor 0x5412
        assert((bits ushr 15) == 0)

        for (index in 0..5) {
            setFunctionModule(8, index, getBit(bits, index))
        }
        setFunctionModule(8, 7, getBit(bits, 6))
        setFunctionModule(8, 8, getBit(bits, 7))
        setFunctionModule(7, 8, getBit(bits, 8))
        for (index in 9 until 15) {
            setFunctionModule(14 - index, 8, getBit(bits, index))
        }

        for (index in 0 until 8) {
            setFunctionModule(size - 1 - index, 8, getBit(bits, index))
        }
        for (index in 8 until 15) {
            setFunctionModule(8, size - 15 + index, getBit(bits, index))
        }
        setFunctionModule(8, size - 8, true)
    }

    /** Draws version information for symbols at version 7 and above. */
    private fun drawVersion() {
        if (version < 7) return

        var remainder = version
        repeat(12) {
            remainder = (remainder shl 1) xor ((remainder ushr 11) * 0x1F25)
        }
        val bits = (version shl 12) or remainder
        assert((bits ushr 18) == 0)

        for (index in 0 until 18) {
            val bit = getBit(bits, index)
            val x = size - 11 + index % 3
            val y = index / 3
            setFunctionModule(x, y, bit)
            setFunctionModule(y, x, bit)
        }
    }

    /** Draws a 9-by-9 finder pattern with its separator, clipped to the symbol. */
    private fun drawFinderPattern(x: Int, y: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val distance = maxOf(abs(dx), abs(dy))
                val column = x + dx
                val row = y + dy
                if (column in 0 until size && row in 0 until size) {
                    setFunctionModule(column, row, distance != 2 && distance != 4)
                }
            }
        }
    }

    /** Draws a 5-by-5 alignment pattern centered at the given module. */
    private fun drawAlignmentPattern(x: Int, y: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                setFunctionModule(x + dx, y + dy, maxOf(abs(dx), abs(dy)) != 1)
            }
        }
    }

    private fun setFunctionModule(x: Int, y: Int, dark: Boolean) {
        modules[y][x] = dark
        functionModules[y][x] = true
    }

    private fun addEccAndInterleave(data: ByteArray): ByteArray {
        require(data.size == getNumDataCodewords(version, errorCorrectionLevel))

        val level = errorCorrectionLevel.ordinal
        val blockCount = NUM_ERROR_CORRECTION_BLOCKS[level][version].toInt()
        val eccLength = ECC_CODEWORDS_PER_BLOCK[level][version].toInt()
        val rawCodewords = getNumRawDataModules(version) / 8
        val shortBlockCount = blockCount - rawCodewords % blockCount
        val shortBlockLength = rawCodewords / blockCount
        val shortDataLength = shortBlockLength - eccLength
        val divisor = reedSolomonComputeDivisor(eccLength)

        // Leave one padding byte in each short block to align its ECC bytes with the long blocks.
        var offset = 0
        val blocks = Array(blockCount) { index ->
            val dataLength = shortDataLength + if (index < shortBlockCount) 0 else 1
            val blockData = data.copyOfRange(offset, offset + dataLength)
            offset += dataLength
            val block = blockData.copyOf(shortBlockLength + 1)
            val ecc = reedSolomonComputeRemainder(blockData, divisor)
            ecc.copyInto(block, block.size - eccLength)
            block
        }

        val result = ByteArray(rawCodewords)
        offset = 0
        for (column in blocks[0].indices) {
            for (index in blocks.indices) {
                if (column != shortDataLength || index >= shortBlockCount) {
                    result[offset] = blocks[index][column]
                    offset++
                }
            }
        }
        return result
    }

    /** Places data and error correction codewords in the two-column zigzag pattern. */
    private fun drawCodewords(data: ByteArray) {
        require(data.size == getNumRawDataModules(version) / 8)
        val bitCount = data.size * 8
        var bitIndex = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5 // Skip the vertical timing column.
            val upward = ((right + 1) and 2) == 0
            for (row in 0 until size) {
                val y = if (upward) size - 1 - row else row
                for (x in right downTo right - 1) {
                    if (!functionModules[y][x] && bitIndex < bitCount) {
                        val codeword = data[bitIndex ushr 3].toInt()
                        modules[y][x] = getBit(codeword, 7 - (bitIndex and 7))
                        bitIndex++
                    }
                }
            }
            right -= 2
        }
        assert(bitIndex == bitCount)
    }

    /** XORs the selected mask into the data modules, including any remainder bits. */
    private fun applyMask(mask: Int) {
        require(mask in 0..7) { "Mask value out of range" }
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (functionModules[y][x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    7 -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    else -> throw AssertionError()
                }
                modules[y][x] = modules[y][x] xor invert
            }
        }
    }

    /** Scores a masked symbol using the Model 2 penalty rules. */
    private fun getPenaltyScore(): Int {
        var result = 0

        // Penalize long same-color runs and finder-like patterns in each direction.
        for (y in 0 until size) {
            var runColor = false
            var runLength = 0
            val runHistory = IntArray(7)
            for (x in 0 until size) {
                val color = modules[y][x]
                if (color == runColor) {
                    runLength++
                    if (runLength == 5) result += PENALTY_N1
                    else if (runLength > 5) result++
                } else {
                    finderPenaltyAddHistory(runLength, runHistory)
                    if (!runColor) result += finderPenaltyCountPatterns(runHistory) * PENALTY_N3
                    runColor = color
                    runLength = 1
                }
            }
            result += finderPenaltyTerminateAndCount(runColor, runLength, runHistory) * PENALTY_N3
        }
        for (x in 0 until size) {
            var runColor = false
            var runLength = 0
            val runHistory = IntArray(7)
            for (y in 0 until size) {
                val color = modules[y][x]
                if (color == runColor) {
                    runLength++
                    if (runLength == 5) result += PENALTY_N1
                    else if (runLength > 5) result++
                } else {
                    finderPenaltyAddHistory(runLength, runHistory)
                    if (!runColor) result += finderPenaltyCountPatterns(runHistory) * PENALTY_N3
                    runColor = color
                    runLength = 1
                }
            }
            result += finderPenaltyTerminateAndCount(runColor, runLength, runHistory) * PENALTY_N3
        }

        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val color = modules[y][x]
                if (color == modules[y][x + 1] && color == modules[y + 1][x] && color == modules[y + 1][x + 1]) {
                    result += PENALTY_N2
                }
            }
        }

        val dark = modules.sumOf { row -> row.count { it } }
        val total = size * size
        val deviation = (abs(dark * 20 - total * 10) + total - 1) / total - 1
        assert(deviation in 0..9)
        result += deviation * PENALTY_N4
        assert(result in 0..2_568_888)
        return result
    }

    private fun finderPenaltyCountPatterns(history: IntArray): Int {
        val unit = history[1]
        assert(unit <= size * 3)
        if (unit == 0 || history[2] != unit || history[3] != unit * 3 || history[4] != unit || history[5] != unit) {
            return 0
        }
        val lightBefore = if (history[0] >= unit * 4 && history[6] >= unit) 1 else 0
        val lightAfter = if (history[6] >= unit * 4 && history[0] >= unit) 1 else 0
        return lightBefore + lightAfter
    }

    private fun finderPenaltyTerminateAndCount(darkRun: Boolean, length: Int, history: IntArray): Int {
        if (darkRun) finderPenaltyAddHistory(length, history)
        val lastLightRun = if (darkRun) 0 else length
        finderPenaltyAddHistory(lastLightRun + size, history)
        return finderPenaltyCountPatterns(history)
    }

    private fun finderPenaltyAddHistory(length: Int, history: IntArray) {
        val runLength = if (history[0] == 0) length + size else length
        history.copyInto(history, destinationOffset = 1, endIndex = history.lastIndex)
        history[0] = runLength
    }

    private fun getAlignmentPatternPositions(): IntArray {
        if (version == 1) return IntArray(0)
        val count = version / 7 + 2
        // Version 32 uses spacing 26 instead of the general spacing formula.
        val step = if (version == 32) 26 else (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
        val positions = IntArray(count)
        positions[0] = 6
        var position = size - 7
        for (index in positions.lastIndex downTo 1) {
            positions[index] = position
            position -= step
        }
        return positions
    }

    // Keep the entries in ascending order of error protection for automatic ECC selection.
    enum class Ecc(internal val formatBits: Int) {
        LOW(1),
        MEDIUM(0),
        QUARTILE(3),
        HIGH(2),
    }

    companion object {
        const val MIN_VERSION = 1
        const val MAX_VERSION = 40

        private const val PENALTY_N1 = 3
        private const val PENALTY_N2 = 3
        private const val PENALTY_N3 = 40
        private const val PENALTY_N4 = 10

        @JvmStatic
        fun encodeText(text: CharSequence, ecl: Ecc): QrCode = encodeSegments(QrSegment.makeSegments(text), ecl)

        @JvmStatic
        fun encodeBinary(data: ByteArray, ecl: Ecc): QrCode = encodeSegments(listOf(QrSegment.makeBytes(data)), ecl)

        @JvmStatic
        fun encodeSegments(segments: List<QrSegment>, ecl: Ecc): QrCode =
            encodeSegments(segments, ecl, MIN_VERSION, MAX_VERSION, -1, true)

        /**
         * Chooses the smallest version within the given range that fits [segments].
         * A [mask] of -1 selects the lowest-penalty mask. [boostEcl] allows a stronger
         * correction level when it fits in the selected version.
         */
        @JvmStatic
        fun encodeSegments(
            segments: List<QrSegment>,
            ecl: Ecc,
            minVersion: Int,
            maxVersion: Int,
            mask: Int,
            boostEcl: Boolean,
        ): QrCode {
            require(minVersion in MIN_VERSION..MAX_VERSION && maxVersion in minVersion..MAX_VERSION && mask in -1..7) {
                "Invalid value"
            }

            var version = minVersion
            var usedBits: Int
            while (true) {
                val capacity = getNumDataCodewords(version, ecl) * 8
                usedBits = QrSegment.getTotalBits(segments, version)
                if (usedBits in 0..capacity) break
                if (version == maxVersion) {
                    val message = if (usedBits < 0) "Segment too long" else {
                        "Data length = $usedBits bits, Max capacity = $capacity bits"
                    }
                    throw DataTooLongException(message)
                }
                version++
            }

            var level = ecl
            if (boostEcl) {
                for (candidate in Ecc.entries) {
                    if (usedBits <= getNumDataCodewords(version, candidate) * 8) level = candidate
                }
            }

            val bits = BitBuffer()
            for (segment in segments) {
                bits.appendBits(segment.mode.modeBits, 4)
                bits.appendBits(segment.numChars, segment.mode.numCharCountBits(version))
                bits.appendData(segment.bits)
            }
            assert(bits.bitLength() == usedBits)

            // End the data, align to a byte boundary, and fill capacity with alternating padding bytes.
            val capacity = getNumDataCodewords(version, level) * 8
            bits.appendBits(0, minOf(4, capacity - bits.bitLength()))
            bits.appendBits(0, (8 - bits.bitLength() % 8) % 8)
            var padByte = 0xEC
            while (bits.bitLength() < capacity) {
                bits.appendBits(padByte, 8)
                padByte = padByte xor (0xEC xor 0x11)
            }

            val data = ByteArray(bits.bitLength() / 8)
            for (index in 0 until bits.bitLength()) {
                val offset = index ushr 3
                val bit = bits.getBit(index) shl (7 - (index and 7))
                data[offset] = (data[offset].toInt() or bit).toByte()
            }
            return QrCode(version, level, data, mask)
        }

        private fun getNumRawDataModules(version: Int): Int {
            require(version in MIN_VERSION..MAX_VERSION) { "Version number out of range" }

            val size = version * 4 + 17
            var result = size * size
            result -= 8 * 8 * 3 // Finder patterns and separators.
            result -= 15 * 2 + 1 // Format bits and the fixed dark module.
            result -= (size - 16) * 2 // Timing patterns between the finders.
            if (version >= 2) {
                val count = version / 7 + 2
                result -= (count - 1) * (count - 1) * 25 // Alignment patterns away from timing patterns.
                result -= (count - 2) * 2 * 20 // Alignment patterns overlapping timing patterns.
                if (version >= 7) result -= 6 * 3 * 2 // Version information.
            }
            assert(result in 208..29_648)
            return result
        }

        internal fun getNumDataCodewords(version: Int, ecl: Ecc): Int {
            val level = ecl.ordinal
            return getNumRawDataModules(version) / 8 -
                ECC_CODEWORDS_PER_BLOCK[level][version] * NUM_ERROR_CORRECTION_BLOCKS[level][version]
        }

        /** Builds the Reed–Solomon generator polynomial over GF(256). */
        private fun reedSolomonComputeDivisor(degree: Int): ByteArray {
            require(degree in 1..255) { "Degree out of range" }
            // Store coefficients from highest to lowest power, omitting the leading 1.
            val result = ByteArray(degree)
            result[result.lastIndex] = 1
            var root = 1
            repeat(degree) {
                for (index in result.indices) {
                    var coefficient = reedSolomonMultiply(result[index].toInt() and 0xFF, root)
                    if (index < result.lastIndex) coefficient = coefficient xor (result[index + 1].toInt() and 0xFF)
                    result[index] = coefficient.toByte()
                }
                root = reedSolomonMultiply(root, 0x02)
            }
            return result
        }

        /** Returns the Reed–Solomon remainder for the given data and generator. */
        private fun reedSolomonComputeRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val result = ByteArray(divisor.size)
            for (byte in data) {
                val factor = (byte.toInt() xor result[0].toInt()) and 0xFF
                result.copyInto(result, startIndex = 1)
                result[result.lastIndex] = 0
                for (index in result.indices) {
                    val coefficient = reedSolomonMultiply(divisor[index].toInt() and 0xFF, factor)
                    result[index] = (result[index].toInt() xor coefficient).toByte()
                }
            }
            return result
        }

        private fun reedSolomonMultiply(x: Int, y: Int): Int {
            assert(x in 0..255 && y in 0..255)
            var result = 0
            for (index in 7 downTo 0) {
                result = (result shl 1) xor ((result ushr 7) * 0x11D)
                result = result xor (((y ushr index) and 1) * x)
            }
            assert((result ushr 8) == 0)
            return result
        }

        internal fun getBit(value: Int, index: Int): Boolean = ((value ushr index) and 1) != 0

        // Columns are QR versions 1–40. Index 0 is padding.
        private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
            byteArrayOf( // Low
                -1,  7, 10, 15, 20, 26, 18, 20, 24, 30, 18,
                20, 24, 26, 30, 22, 24, 28, 30, 28, 28,
                28, 28, 30, 30, 26, 28, 30, 30, 30, 30,
                30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
            ),
            byteArrayOf( // Medium
                -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26,
                30, 22, 22, 24, 24, 28, 28, 26, 26, 26,
                26, 28, 28, 28, 28, 28, 28, 28, 28, 28,
                28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
            ),
            byteArrayOf( // Quartile
                -1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24,
                28, 26, 24, 20, 30, 24, 28, 28, 26, 30,
                28, 30, 30, 30, 30, 28, 30, 30, 30, 30,
                30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
            ),
            byteArrayOf( // High
                -1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28,
                24, 28, 22, 24, 24, 30, 28, 28, 26, 28,
                30, 24, 30, 30, 30, 30, 30, 30, 30, 30,
                30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
            ),
        )

        private val NUM_ERROR_CORRECTION_BLOCKS = arrayOf(
            byteArrayOf( // Low
                -1,  1,  1,  1,  1,  1,  2,  2,  2,  2,  4,
                 4,  4,  4,  4,  6,  6,  6,  6,  7,  8,
                 8,  9,  9, 10, 12, 12, 12, 13, 14, 15,
                16, 17, 18, 19, 19, 20, 21, 22, 24, 25,
            ),
            byteArrayOf( // Medium
                -1,  1,  1,  1,  2,  2,  4,  4,  4,  5,  5,
                 5,  8,  9,  9, 10, 10, 11, 13, 14, 16,
                17, 17, 18, 20, 21, 23, 25, 26, 28, 29,
                31, 33, 35, 37, 38, 40, 43, 45, 47, 49,
            ),
            byteArrayOf( // Quartile
                -1,  1,  1,  2,  2,  4,  4,  6,  6,  8,  8,
                 8, 10, 12, 16, 12, 17, 16, 18, 21, 20,
                23, 23, 25, 27, 29, 34, 34, 35, 38, 40,
                43, 45, 48, 51, 53, 56, 59, 62, 65, 68,
            ),
            byteArrayOf( // High
                -1,  1,  1,  2,  4,  4,  4,  5,  6,  8,  8,
                11, 11, 16, 16, 18, 16, 19, 21, 25, 25,
                25, 34, 30, 32, 35, 37, 40, 42, 45, 48,
                51, 54, 57, 60, 63, 66, 70, 74, 77, 81,
            ),
        )
    }
}
