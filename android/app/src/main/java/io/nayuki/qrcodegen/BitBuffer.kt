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

import java.util.BitSet

/** An appendable sequence of bits used to build [QrSegment]s. */
class BitBuffer private constructor(
    private val data: BitSet,
    private var length: Int,
) : Cloneable {
    constructor() : this(BitSet(), 0)

    fun bitLength(): Int = length

    fun getBit(index: Int): Int {
        if (index !in 0 until length) throw IndexOutOfBoundsException()
        return if (data[index]) 1 else 0
    }

    /** Appends [count] low-order bits of [value], most significant bit first. */
    fun appendBits(value: Int, count: Int) {
        require(count in 0..31 && (value ushr count) == 0) { "Value out of range" }
        check(count <= Int.MAX_VALUE - length) { "Maximum length reached" }
        for (index in count - 1 downTo 0) {
            data.set(length, QrCode.getBit(value, index))
            length++
        }
    }

    fun appendData(source: BitBuffer) {
        check(source.length <= Int.MAX_VALUE - length) { "Maximum length reached" }
        for (index in 0 until source.length) {
            data.set(length, source.data[index])
            length++
        }
    }

    public override fun clone(): BitBuffer = BitBuffer(data.clone() as BitSet, length)
}
