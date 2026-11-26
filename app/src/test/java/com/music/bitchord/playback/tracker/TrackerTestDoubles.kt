package com.music.bitchord.playback.tracker

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.EOFException

/** In-memory [ExtractorInput] that records reads so tests can prove PCM reads touch no file bytes. */
internal class ByteArrayExtractorInput(
    private val data: ByteArray,
    private val declaredLength: Long = data.size.toLong(),
) : ExtractorInput {
    private var position = 0L
    private var peekPosition = 0L
    var readCalls = 0
        private set
    var peekCalls = 0
        private set

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        readCalls++
        if (position >= data.size) return -1
        val count = minOf(length, (data.size - position).toInt())
        data.copyInto(target, offset, position.toInt(), position.toInt() + count)
        position += count
        return count
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        readCalls++
        val available = data.size - position
        if (available < length) {
            if (!allowEndOfInput) throw EOFException()
            if (available > 0) data.copyInto(target, offset, position.toInt(), data.size)
            position = data.size.toLong()
            return false
        }
        data.copyInto(target, offset, position.toInt(), position.toInt() + length)
        position += length
        return true
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        if (!readFully(target, offset, length, false)) throw EOFException()
    }

    override fun skip(length: Int): Int {
        readCalls++
        val count = minOf(length, (data.size - position).toInt()).coerceAtLeast(0)
        position += count
        return count
    }

    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
        readCalls++
        val skipped = skip(length)
        if (skipped < length && !allowEndOfInput) throw EOFException()
        return skipped == length
    }

    override fun skipFully(length: Int) {
        if (!skipFully(length, false)) throw EOFException()
    }

    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        peekCalls++
        val count = minOf(length, (data.size - peekPosition).toInt()).coerceAtLeast(0)
        if (count > 0) data.copyInto(target, offset, peekPosition.toInt(), peekPosition.toInt() + count)
        return count
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        peekCalls++
        val available = data.size - peekPosition
        if (available < length) {
            if (!allowEndOfInput) throw EOFException()
            if (available > 0) data.copyInto(target, offset, peekPosition.toInt(), data.size)
            return false
        }
        data.copyInto(target, offset, peekPosition.toInt(), peekPosition.toInt() + length)
        return true
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        if (!peekFully(target, offset, length, false)) throw EOFException()
    }

    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        val advanced = minOf(length, (data.size - peekPosition).toInt()).coerceAtLeast(0)
        peekPosition += advanced
        if (advanced < length && !allowEndOfInput) throw EOFException()
        return advanced == length
    }

    override fun advancePeekPosition(length: Int) {
        if (!advancePeekPosition(length, false)) throw EOFException()
    }

    override fun resetPeekPosition() {
        peekPosition = position
    }

    override fun getPeekPosition(): Long = peekPosition
    override fun getPosition(): Long = position
    override fun getLength(): Long = declaredLength

    override fun <E : Throwable> setRetryPosition(position: Long, throwable: E) {
        this.position = position
        peekPosition = position
        throw throwable
    }
}

/** Marker used to prove the wrapped delegate stays in the array, in order, after the tracker. */
internal class MarkerExtractor(val name: String) : Extractor {
    var released = false

    override fun sniff(input: ExtractorInput): Boolean = false
    override fun init(output: ExtractorOutput) {}
    override fun read(input: ExtractorInput, seekPosition: androidx.media3.extractor.PositionHolder): Int =
        Extractor.RESULT_END_OF_INPUT
    override fun seek(position: Long, timeUs: Long) {}
    override fun release() {
        released = true
    }
}

internal class RecordingExtractorsFactory(
    val extractor: Extractor = MarkerExtractor("delegate"),
) : androidx.media3.extractor.ExtractorsFactory {
    var uri: android.net.Uri? = null
    var headers: Map<String, List<String>>? = null
    var uriCallCount = 0
    var noArgCallCount = 0

    override fun createExtractors(): Array<Extractor> {
        noArgCallCount++
        return arrayOf(extractor)
    }

    override fun createExtractors(
        uri: android.net.Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> {
        this.uri = uri
        this.headers = responseHeaders
        uriCallCount++
        return arrayOf(extractor)
    }
}

internal class RecordingExtractorOutput : ExtractorOutput {
    val trackOutput = RecordingTrackOutput()
    var format: Format? = null
    var seekMap: SeekMap? = null
    var endedTracks = false

    override fun track(id: Int, type: Int): TrackOutput {
        check(id == 0 && type == C.TRACK_TYPE_AUDIO)
        return trackOutput
    }

    override fun seekMap(seekMap: SeekMap) {
        this.seekMap = seekMap
    }

    override fun endTracks() {
        endedTracks = true
    }
}

internal class RecordingTrackOutput : TrackOutput {
    data class Sample(
        val timeUs: Long,
        val flags: Int,
        val byteCount: Int,
        val offset: Int,
        val dataReader: DataReader,
        val bytes: ByteArray,
    )

    var format: Format? = null
    val samples = mutableListOf<Sample>()
    private val sink = ByteArray(256 * 1024)

    override fun format(format: Format) {
        this.format = format
    }

    override fun sampleData(
        dataReader: DataReader,
        sampleCount: Int,
        isSyncSample: Boolean,
        samplePart: Int,
    ): Int {
        check(samplePart == TrackOutput.SAMPLE_DATA_PART_MAIN)
        check(sampleCount > 0 && sampleCount <= sink.size)
        var total = 0
        while (total < sampleCount) {
            val count = dataReader.read(sink, total, sampleCount - total)
            if (count < 0) break
            total += count
        }
        check(total == sampleCount) { "reader supplied $total of $sampleCount bytes" }
        pendingReader = dataReader
        pendingCount = sampleCount
        return sampleCount
    }

    override fun sampleData(data: ParsableByteArray, sampleCount: Int, sampleOffset: Int) {
        throw UnsupportedOperationException("tracker output must use the reused DataReader path")
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        sampleCount: Int,
        sampleOffset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        check(cryptoData == null)
        val reader = checkNotNull(pendingReader)
        samples += Sample(
            timeUs,
            flags,
            pendingCount,
            sampleOffset,
            reader,
            sink.copyOfRange(0, pendingCount),
        )
        pendingReader = null
    }

    private var pendingReader: DataReader? = null
    private var pendingCount = 0
}

internal fun ByteArray.putAscii(offset: Int, value: String) {
    for (index in value.indices) this[offset + index] = value[index].code.toByte()
}

internal fun ByteArray.putIntBigEndian(offset: Int, value: Int) {
    put(offset, (value ushr 24).toByte())
    put(offset + 1, (value ushr 16).toByte())
    put(offset + 2, (value ushr 8).toByte())
    put(offset + 3, value.toByte())
}

private fun ByteArray.put(offset: Int, value: Byte) {
    this[offset] = value
}
