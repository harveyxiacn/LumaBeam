package com.lumabeam.core

/**
 * Minimal QR Code encoder: byte mode, error-correction level M, versions 1-10
 * (up to 213 bytes), which is plenty for a LAN URL.
 *
 * [modules] is indexed [row][column]; true = dark.
 */
class QrCode private constructor(val version: Int) {
    val size = version * 4 + 17
    val modules = Array(size) { BooleanArray(size) }
    private val reserved = Array(size) { BooleanArray(size) }

    companion object {
        // Per version (index 0 = version 1), level M: EC codewords per block, then (count, data codewords) groups.
        private val EC_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = arrayOf(
            intArrayOf(1, 16), intArrayOf(1, 28), intArrayOf(1, 44), intArrayOf(2, 32), intArrayOf(2, 43),
            intArrayOf(4, 27), intArrayOf(4, 31), intArrayOf(2, 38, 2, 39), intArrayOf(3, 36, 2, 37),
            intArrayOf(4, 43, 1, 44),
        )
        private val ALIGNMENT = arrayOf(
            intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30),
            intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46),
            intArrayOf(6, 28, 50),
        )

        fun encode(text: String): QrCode {
            val data = text.toByteArray(Charsets.UTF_8)
            for (v in 1..10) {
                val capacityBits = dataCodewords(v) * 8
                val countBits = if (v <= 9) 8 else 16
                if (4 + countBits + data.size * 8 <= capacityBits) {
                    return QrCode(v).also { it.build(data) }
                }
            }
            throw IllegalArgumentException("text too long for QR code")
        }

        private fun dataCodewords(version: Int): Int {
            val g = BLOCKS[version - 1]
            var total = 0
            var i = 0
            while (i < g.size) {
                total += g[i] * g[i + 1]
                i += 2
            }
            return total
        }

        // GF(256) with primitive polynomial x^8 + x^4 + x^3 + x^2 + 1.
        private val EXP = IntArray(512)
        private val LOG = IntArray(256)

        init {
            var x = 1
            for (i in 0 until 255) {
                EXP[i] = x
                LOG[x] = i
                x = x shl 1
                if (x and 0x100 != 0) x = x xor 0x11D
            }
            for (i in 255 until 512) EXP[i] = EXP[i - 255]
        }

        private fun gfMul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

        /** Reed-Solomon error-correction codewords for [data]. */
        internal fun reedSolomon(data: IntArray, ecCount: Int): IntArray {
            // Generator polynomial coefficients, highest degree first (leading 1 omitted).
            var gen = intArrayOf(1)
            for (i in 0 until ecCount) {
                val next = IntArray(gen.size + 1)
                for (j in gen.indices) {
                    next[j] = next[j] xor gen[j]
                    next[j + 1] = next[j + 1] xor gfMul(gen[j], EXP[i])
                }
                gen = next
            }
            val rem = IntArray(ecCount)
            for (d in data) {
                val factor = d xor rem[0]
                System.arraycopy(rem, 1, rem, 0, ecCount - 1)
                rem[ecCount - 1] = 0
                for (j in 0 until ecCount) rem[j] = rem[j] xor gfMul(gen[j + 1], factor)
            }
            return rem
        }
    }

    private fun build(data: ByteArray) {
        val codewords = interleave(encodeData(data))
        drawFunctionPatterns()
        placeData(codewords)
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0 until 8) {
            applyMask(mask)
            drawFormat(mask)
            val p = penalty()
            if (p < bestPenalty) {
                bestPenalty = p
                bestMask = mask
            }
            applyMask(mask) // XOR again to undo
        }
        applyMask(bestMask)
        drawFormat(bestMask)
    }

    private fun encodeData(data: ByteArray): IntArray {
        val capacity = dataCodewords(version)
        val bits = ArrayList<Boolean>(capacity * 8)
        fun put(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits += (value ushr i) and 1 == 1
        }
        put(0b0100, 4)
        put(data.size, if (version <= 9) 8 else 16)
        for (b in data) put(b.toInt() and 0xFF, 8)
        put(0, minOf(4, capacity * 8 - bits.size))
        while (bits.size % 8 != 0) bits += false
        val out = IntArray(capacity)
        val used = bits.size / 8
        for (i in 0 until used) {
            var v = 0
            for (j in 0 until 8) v = (v shl 1) or (if (bits[i * 8 + j]) 1 else 0)
            out[i] = v
        }
        for (i in used until capacity) out[i] = if ((i - used) % 2 == 0) 0xEC else 0x11
        return out
    }

    private fun interleave(data: IntArray): IntArray {
        val ecCount = EC_PER_BLOCK[version - 1]
        val groups = BLOCKS[version - 1]
        val dataBlocks = ArrayList<IntArray>()
        var offset = 0
        var g = 0
        while (g < groups.size) {
            repeat(groups[g]) {
                dataBlocks += data.copyOfRange(offset, offset + groups[g + 1])
                offset += groups[g + 1]
            }
            g += 2
        }
        val ecBlocks = dataBlocks.map { reedSolomon(it, ecCount) }
        val result = ArrayList<Int>()
        val maxData = dataBlocks.maxOf { it.size }
        for (i in 0 until maxData) for (b in dataBlocks) if (i < b.size) result += b[i]
        for (i in 0 until ecCount) for (b in ecBlocks) result += b[i]
        return result.toIntArray()
    }

    private fun set(row: Int, col: Int, dark: Boolean) {
        modules[row][col] = dark
        reserved[row][col] = true
    }

    private fun drawFunctionPatterns() {
        for (i in 0 until size) {
            set(6, i, i % 2 == 0)
            set(i, 6, i % 2 == 0)
        }
        drawFinder(3, 3)
        drawFinder(3, size - 4)
        drawFinder(size - 4, 3)
        val align = ALIGNMENT[version - 1]
        for (r in align) for (c in align) {
            val nearFinder = (r == 6 && c == 6) || (r == 6 && c == size - 7) || (r == size - 7 && c == 6)
            if (!nearFinder) drawAlignment(r, c)
        }
        // Reserve format areas now (real bits drawn per mask); includes the dark module.
        drawFormat(0)
        if (version >= 7) {
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val dark = (bits ushr i) and 1 == 1
                val a = size - 11 + i % 3
                val b = i / 3
                set(b, a, dark)
                set(a, b, dark)
            }
        }
    }

    private fun drawFinder(cr: Int, cc: Int) {
        for (dr in -4..4) for (dc in -4..4) {
            val r = cr + dr
            val c = cc + dc
            if (r !in 0 until size || c !in 0 until size) continue
            val dist = maxOf(Math.abs(dr), Math.abs(dc))
            set(r, c, dist != 2 && dist != 4)
        }
    }

    private fun drawAlignment(cr: Int, cc: Int) {
        for (dr in -2..2) for (dc in -2..2) {
            set(cr + dr, cc + dc, maxOf(Math.abs(dr), Math.abs(dc)) != 1)
        }
    }

    private fun drawFormat(mask: Int) {
        val data = (0 shl 3) or mask // level M = 0b00
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = ((data shl 10) or rem) xor 0x5412
        fun bit(i: Int) = (bits ushr i) and 1 == 1
        // Copy 1, around the top-left finder. set(row, col).
        for (i in 0..5) set(i, 8, bit(i))
        set(7, 8, bit(6))
        set(8, 8, bit(7))
        set(8, 7, bit(8))
        for (i in 9 until 15) set(8, 14 - i, bit(i))
        // Copy 2, split between the other two finders.
        for (i in 0 until 8) set(8, size - 1 - i, bit(i))
        for (i in 8 until 15) set(size - 15 + i, 8, bit(i))
        set(size - 8, 8, true)
    }

    private fun placeData(codewords: IntArray) {
        var bitIndex = 0
        val totalBits = codewords.size * 8
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            val upward = ((right + 1) and 2) == 0
            for (vert in 0 until size) {
                val row = if (upward) size - 1 - vert else vert
                for (j in 0..1) {
                    val col = right - j
                    if (reserved[row][col]) continue
                    if (bitIndex < totalBits) {
                        modules[row][col] = (codewords[bitIndex ushr 3] ushr (7 - (bitIndex and 7))) and 1 == 1
                        bitIndex++
                    }
                }
            }
            right -= 2
        }
    }

    private fun applyMask(mask: Int) {
        for (r in 0 until size) for (c in 0 until size) {
            if (reserved[r][c]) continue
            val invert = when (mask) {
                0 -> (r + c) % 2 == 0
                1 -> r % 2 == 0
                2 -> c % 3 == 0
                3 -> (r + c) % 3 == 0
                4 -> (r / 2 + c / 3) % 2 == 0
                5 -> (r * c) % 2 + (r * c) % 3 == 0
                6 -> ((r * c) % 2 + (r * c) % 3) % 2 == 0
                else -> ((r + c) % 2 + (r * c) % 3) % 2 == 0
            }
            if (invert) modules[r][c] = !modules[r][c]
        }
    }

    private fun penalty(): Int {
        var score = 0
        fun at(r: Int, c: Int, horizontal: Boolean) = if (horizontal) modules[r][c] else modules[c][r]
        for (horizontal in listOf(true, false)) {
            for (a in 0 until size) {
                var runColor = at(a, 0, horizontal)
                var run = 1
                for (b in 1 until size) {
                    val m = at(a, b, horizontal)
                    if (m == runColor) {
                        run++
                        if (run == 5) score += 3 else if (run > 5) score++
                    } else {
                        runColor = m
                        run = 1
                    }
                }
                // Finder-like 1:1:3:1:1 with four light modules on one side.
                for (b in 0..size - 11) {
                    val p = BooleanArray(11) { at(a, b + it, horizontal) }
                    val core = p[0] && !p[1] && p[2] && p[3] && p[4] && !p[5] && p[6]
                    if (core && !p[7] && !p[8] && !p[9] && !p[10]) score += 40
                    val core2 = p[4] && !p[5] && p[6] && p[7] && p[8] && !p[9] && p[10]
                    if (core2 && !p[0] && !p[1] && !p[2] && !p[3]) score += 40
                }
            }
        }
        var dark = 0
        for (r in 0 until size) for (c in 0 until size) {
            if (modules[r][c]) dark++
            if (r < size - 1 && c < size - 1) {
                val m = modules[r][c]
                if (modules[r][c + 1] == m && modules[r + 1][c] == m && modules[r + 1][c + 1] == m) score += 3
            }
        }
        val percent = dark * 100 / (size * size)
        score += Math.abs(percent - 50) / 5 * 10
        return score
    }
}
