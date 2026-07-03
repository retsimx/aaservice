package com.air.advantage.aaservice.data.protocol

import java.nio.charset.StandardCharsets
import kotlin.math.abs

object CrcCalculator {

    private val table: IntArray = intArrayOf(
        0, 62, 124, 66, 248, 198, 132, 186, 149, 171, 233, 215, 109, 83, 17, 47,
        79, 113, 51, 13, 183, 137, 203, 245, 218, 228, 166, 152, 34, 28, 94, 96,
        158, 160, 226, 220, 102, 88, 26, 36, 11, 53, 119, 73, 243, 205, 143, 177,
        209, 239, 173, 147, 41, 23, 85, 107, 68, 122, 56, 6, 188, 130, 192, 254,
        89, 103, 37, 27, 161, 159, 221, 227, 204, 242, 176, 142, 52, 10, 72, 118,
        22, 40, 106, 84, 238, 208, 146, 172, 131, 189, 255, 193, 123, 69, 7, 57,
        199, 249, 187, 133, 63, 1, 67, 125, 82, 108, 46, 16, 170, 148, 214, 232,
        136, 182, 244, 202, 112, 78, 12, 50, 29, 35, 97, 95, 229, 219, 153, 167,
        178, 140, 206, 240, 74, 116, 54, 8, 39, 25, 91, 101, 223, 225, 163, 157,
        253, 195, 129, 191, 5, 59, 121, 71, 104, 86, 20, 42, 144, 174, 236, 210,
        44, 18, 80, 110, 212, 234, 168, 150, 185, 135, 197, 251, 65, 127, 61, 3,
        99, 93, 31, 33, 155, 165, 231, 217, 246, 200, 138, 180, 14, 48, 114, 76,
        235, 213, 151, 169, 19, 45, 111, 81, 126, 64, 2, 60, 134, 184, 250, 196,
        164, 154, 216, 230, 92, 98, 32, 30, 49, 15, 77, 115, 201, 247, 181, 139,
        117, 75, 9, 55, 141, 179, 241, 207, 224, 222, 156, 162, 24, 38, 100, 90,
        58, 4, 70, 120, 194, 252, 190, 128, 175, 145, 211, 237, 87, 105, 43, 21
    )

    fun compute(data: ByteArray?, start: Int = 0, end: Int = data?.size ?: 0): Int {
        if (data == null || start > end || end >= data.size) {
            return 255
        }
        var crc = 0
        var i = start
        while (i < end) {
            val idx = data[i].toInt() and 0xFF
            crc = table[crc xor idx]
            i++
        }
        return abs(crc - 255)
    }

    fun computeHex(tag: String?): String {
        if (tag == null || tag.isEmpty()) {
            return "ff"
        }
        val bytes = tag.toByteArray(StandardCharsets.US_ASCII)
        var crc = 0
        for (b in bytes) {
            val idx = b.toInt() and 0xFF
            crc = table[crc xor idx]
        }
        val result = abs(crc - 255)
        return result.toString(16).padStart(2, '0')
    }
}
