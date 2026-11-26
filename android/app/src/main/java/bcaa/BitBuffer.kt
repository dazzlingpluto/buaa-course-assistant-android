package bcaa

import java.util.BitSet

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
