package com.didrikquant.replay.storage

import com.github.luben.zstd.Zstd

public object Compression {

    private const val DEFAULT_LEVEL: Int = 3

    public fun compress(data: ByteArray, level: Int = DEFAULT_LEVEL): ByteArray {
        return Zstd.compress(data, level)
    }

    @Suppress("DEPRECATION")
    public fun decompress(compressed: ByteArray): ByteArray {
        val decompressedSize = Zstd.decompressedSize(compressed)
        require(decompressedSize > 0) { "Invalid compressed data or unknown decompressed size" }
        return Zstd.decompress(compressed, decompressedSize.toInt())
    }

    public fun compressString(data: String, level: Int = DEFAULT_LEVEL): ByteArray {
        return compress(data.toByteArray(Charsets.UTF_8), level)
    }

    public fun decompressString(compressed: ByteArray): String {
        return decompress(compressed).toString(Charsets.UTF_8)
    }
}
