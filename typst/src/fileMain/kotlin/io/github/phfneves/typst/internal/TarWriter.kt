package io.github.phfneves.typst.internal

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Writes a POSIX ustar archive, the plain-tar form the engine accepts besides `.tar.gz`.
 *
 * Only regular files, since that is all a package needs. A path longer than 100 bytes is split
 * into ustar's 155-byte prefix and 100-byte name at a slash.
 */
internal class TarWriter {

    private val out = Buffer()

    fun add(path: String, contents: ByteArray) {
        val header = ByteArray(BLOCK)
        val (prefix, name) = splitPath(path)
        header.put(0, 100, name)
        header.putOctal(100, 8, 0b110_100_100) // mode 0644
        header.putOctal(108, 8, 0) // uid
        header.putOctal(116, 8, 0) // gid
        header.putOctal(124, 12, contents.size.toLong())
        header.putOctal(136, 12, 0) // mtime: fixed, so the same directory gives the same bytes
        header[156] = '0'.code.toByte() // regular file
        header.put(257, 6, "ustar".encodeToByteArray() + 0)
        header.put(263, 2, "00".encodeToByteArray())
        header.put(345, 155, prefix)

        // The checksum is computed with its own field read as eight spaces.
        for (index in 148 until 156) header[index] = ' '.code.toByte()
        val checksum = header.sumOf { it.toInt() and 0xff }
        header.put(148, 8, (checksum.toString(8).padStart(6, '0')).encodeToByteArray() + 0 + 32)

        out.write(header)
        out.write(contents)
        val padding = (BLOCK - contents.size % BLOCK) % BLOCK
        out.write(ByteArray(padding))
    }

    /** Ends the archive with the two empty blocks tar requires and returns its bytes. */
    fun finish(): ByteArray {
        out.write(ByteArray(BLOCK * 2))
        return out.readByteArray()
    }

    private fun splitPath(path: String): Pair<ByteArray, ByteArray> {
        val bytes = path.encodeToByteArray()
        if (bytes.size <= 100) return ByteArray(0) to bytes
        // The rightmost slash that leaves a name of at most 100 bytes and a prefix of at most 155.
        for (index in bytes.indices.reversed()) {
            if (bytes[index] != '/'.code.toByte()) continue
            val name = bytes.copyOfRange(index + 1, bytes.size)
            val prefix = bytes.copyOfRange(0, index)
            if (name.size > 100) break
            if (prefix.size <= 155 && name.isNotEmpty()) return prefix to name
        }
        throw IllegalArgumentException("Path too long for a tar archive: $path")
    }

    private fun ByteArray.put(offset: Int, length: Int, value: ByteArray) {
        require(value.size <= length)
        value.copyInto(this, offset)
    }

    private fun ByteArray.putOctal(offset: Int, length: Int, value: Long) {
        put(offset, length, value.toString(8).padStart(length - 1, '0').encodeToByteArray() + 0)
    }

    private companion object {
        const val BLOCK = 512
    }
}
