package com.music.bitchord.playback.decoder

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Small random-access contract shared by the ISO parser and its playback reader. */
interface RandomAccessSource {
    val length: Long

    fun readFully(position: Long, buffer: ByteArray, offset: Int = 0, size: Int = buffer.size - offset)
}

/** In-memory source, useful for small images and deterministic parser tests. */
class ByteArrayRandomAccessSource(private val bytes: ByteArray) : RandomAccessSource {
    override val length: Long get() = bytes.size.toLong()

    override fun readFully(position: Long, buffer: ByteArray, offset: Int, size: Int) {
        checkRead(position, buffer, offset, size, length)
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + size)
    }
}

/** Local-file implementation. A Media3 DataSource adapter can implement the same contract. */
class FileRandomAccessSource(file: File) : RandomAccessSource, Closeable {
    private val file = RandomAccessFile(file, "r")
    override val length: Long = file.length()

    @Synchronized
    override fun readFully(position: Long, buffer: ByteArray, offset: Int, size: Int) {
        checkRead(position, buffer, offset, size, length)
        file.seek(position)
        file.readFully(buffer, offset, size)
    }

    override fun close() = file.close()
}

internal fun checkRead(position: Long, buffer: ByteArray, offset: Int, size: Int, length: Long) {
    require(offset >= 0 && size >= 0 && offset <= buffer.size - size) { "Invalid read buffer range." }
    require(position >= 0 && position <= length && size.toLong() <= length - position) {
        "Read exceeds the source length."
    }
}

internal fun RandomAccessSource.readBytes(position: Long, size: Int): ByteArray {
    require(size >= 0)
    return ByteArray(size).also { readFully(position, it) }
}
