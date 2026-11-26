/*
 * QR Code generator library (Kotlin port)
 *
 * Copyright (c) Project Nayuki. (MIT License)
 * https://www.nayuki.io/page/qr-code-generator-library
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 * - The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 * - The Software is provided "as is", without warranty of any kind, express or
 *   implied, including but not limited to the warranties of merchantability,
 *   fitness for a particular purpose and noninfringement. In no event shall the
 *   authors or copyright holders be liable for any claim, damages or other
 *   liability, whether in an action of contract, tort or otherwise, arising from,
 *   out of or in connection with the Software or the use or other dealings in the
 *   Software.
 */

package io.nayuki.qrcodegen

/** An immutable segment of character, binary, or control data in a QR code. */
class QrSegment(
    @JvmField val mode: Mode,
    @JvmField val numChars: Int,
    data: BitBuffer,
) {
    init {
        require(numChars >= 0) { "Invalid value" }
    }

    internal val bits = data.clone()

    fun getData(): BitBuffer = bits.clone()

    enum class Mode(internal val modeBits: Int, private vararg val charCountBits: Int) {
        NUMERIC(0x1, 10, 12, 14),
        ALPHANUMERIC(0x2, 9, 11, 13),
        BYTE(0x4, 8, 16, 16),
        KANJI(0x8, 8, 10, 12),
        ECI(0x7, 0, 0, 0);

        internal fun numCharCountBits(version: Int): Int {
            assert(version in QrCode.MIN_VERSION..QrCode.MAX_VERSION)
            return charCountBits[(version + 7) / 17]
        }
    }

    companion object {
        private const val ALPHANUMERIC_CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"
        private val NUMERIC_REGEX = Regex("[0-9]*")
        private val ALPHANUMERIC_REGEX = Regex("[A-Z0-9 \$%*+./:-]*")

        @JvmStatic
        fun makeBytes(data: ByteArray): QrSegment {
            val bits = BitBuffer()
            for (byte in data) {
                bits.appendBits(byte.toInt() and 0xFF, 8)
            }
            return QrSegment(Mode.BYTE, data.size, bits)
        }

        @JvmStatic
        fun makeNumeric(digits: CharSequence): QrSegment {
            require(isNumeric(digits)) { "String contains non-numeric characters" }
            val bits = BitBuffer()
            for (index in digits.indices step 3) {
                val count = minOf(3, digits.length - index)
                val value = digits.subSequence(index, index + count).toString().toInt()
                bits.appendBits(value, count * 3 + 1)
            }
            return QrSegment(Mode.NUMERIC, digits.length, bits)
        }

        @JvmStatic
        fun makeAlphanumeric(text: CharSequence): QrSegment {
            require(isAlphanumeric(text)) { "String contains unencodable characters in alphanumeric mode" }
            val bits = BitBuffer()
            for (index in 0 until (text.length - 1) step 2) {
                val first = ALPHANUMERIC_CHARSET.indexOf(text[index])
                val second = ALPHANUMERIC_CHARSET.indexOf(text[index + 1])
                bits.appendBits(first * 45 + second, 11)
            }
            if (text.length % 2 != 0) {
                bits.appendBits(ALPHANUMERIC_CHARSET.indexOf(text.last()), 6)
            }
            return QrSegment(Mode.ALPHANUMERIC, text.length, bits)
        }

        /** Chooses the most compact supported mode for the entire text. */
        @JvmStatic
        fun makeSegments(text: CharSequence): MutableList<QrSegment> {
            if (text == "") return mutableListOf()
            val segment = when {
                isNumeric(text) -> makeNumeric(text)
                isAlphanumeric(text) -> makeAlphanumeric(text)
                else -> makeBytes(text.toString().toByteArray(Charsets.UTF_8))
            }
            return mutableListOf(segment)
        }

        /** Encodes an Extended Channel Interpretation (ECI) assignment. */
        @JvmStatic
        fun makeEci(assignment: Int): QrSegment {
            require(assignment in 0 until 1_000_000) { "ECI assignment value out of range" }
            val bits = BitBuffer()
            when {
                assignment < (1 shl 7) -> bits.appendBits(assignment, 8)
                assignment < (1 shl 14) -> {
                    bits.appendBits(0b10, 2)
                    bits.appendBits(assignment, 14)
                }
                else -> {
                    bits.appendBits(0b110, 3)
                    bits.appendBits(assignment, 21)
                }
            }
            return QrSegment(Mode.ECI, 0, bits)
        }

        @JvmStatic
        fun isNumeric(text: CharSequence): Boolean = NUMERIC_REGEX.matches(text)

        @JvmStatic
        fun isAlphanumeric(text: CharSequence): Boolean = ALPHANUMERIC_REGEX.matches(text)

        /** Returns -1 if a character count or total bit count cannot be represented. */
        internal fun getTotalBits(segments: List<QrSegment>, version: Int): Int {
            var total = 0L
            for (segment in segments) {
                val countBits = segment.mode.numCharCountBits(version)
                if (segment.numChars >= (1 shl countBits)) return -1
                total += 4L + countBits + segment.bits.bitLength()
                if (total > Int.MAX_VALUE) return -1
            }
            return total.toInt()
        }
    }
}
