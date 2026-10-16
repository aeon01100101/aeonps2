package com.aeonps2.firetv

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Scans the game library and pulls PS2 disc serials out of disc images.
 *
 * Serial extraction reads the ISO9660 volume: sector 16 is the Primary
 * Volume Descriptor, which points at the root directory; the root
 * directory holds SYSTEM.CNF; SYSTEM.CNF's BOOT2 line carries a token
 * like "SLUS_203.12;1", normalized to "SLUS-20312".
 *
 * Pure JVM (java.io only, no Android imports) and never throws: garbage
 * in means "" out.
 */
object GameScanner {

    data class Entry(
        val path: String,
        val title: String,
        val format: String,
        val serial: String,
        val mode: String
    )

    val EXTENSIONS: Set<String> =
        setOf("iso", "chd", "cue", "m3u", "cso", "bin", "mdf", "nrg", "elf")

    private const val MAX_DEPTH = 3

    /**
     * [modeOf] maps a game path to its remembered mode ("fat"/"slim");
     * callers supply it so the scanner itself stays Android-free.
     */
    fun scan(
        folders: Set<String>,
        added: Set<String>,
        modeOf: (String) -> String = { Store.MODE_FAT }
    ): List<Entry> {
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()

        fun addFile(f: File) {
            try {
                if (!f.isFile || !f.canRead()) return
                val ext = f.extension.lowercase(Locale.US)
                if (ext !in EXTENSIONS) return
                if (ext == "bin") {
                    // A .bin sitting next to a same-name .cue is launched via the cue.
                    val cue = File(f.parent, f.nameWithoutExtension + ".cue")
                    if (cue.isFile) return
                }
                if (seen.add(f.absolutePath)) {
                    val serial =
                        if (ext == "iso" || ext == "bin") SerialReader.extractSerial(f) else ""
                    out.add(
                        Entry(
                            path = f.absolutePath,
                            title = f.nameWithoutExtension.replace('_', ' '),
                            format = ext.uppercase(Locale.US),
                            serial = serial,
                            mode = modeOf(f.absolutePath)
                        )
                    )
                }
            } catch (e: Exception) {
                // One bad file never kills the scan.
            }
        }

        for (folder in folders) {
            try {
                val root = File(folder)
                if (!root.isDirectory || !root.canRead()) continue
                walk(root, 0, ::addFile)
            } catch (e: Exception) {
                // One bad folder never kills the scan.
            }
        }

        for (p in added) {
            try {
                addFile(File(p))
            } catch (e: Exception) {
                // Ignore.
            }
        }

        return out.sortedBy { it.title.lowercase(Locale.US) }
    }

    private fun walk(dir: File, depth: Int, add: (File) -> Unit) {
        val kids = try {
            dir.listFiles()
        } catch (e: Exception) {
            null
        } ?: return
        for (k in kids) {
            try {
                when {
                    k.isDirectory && !k.isHidden && depth < MAX_DEPTH -> walk(k, depth + 1, add)
                    k.isFile -> add(k)
                }
            } catch (e: Exception) {
                // Keep going.
            }
        }
    }
}

/**
 * ISO9660 SYSTEM.CNF serial reader. Handles 2048-byte sectors (DVD .iso)
 * and 2352-byte raw sectors (CD .bin, Mode 2 Form 1/2 data at +24, Mode 1
 * fallback at +16). Never throws; returns "" on anything unexpected.
 */
object SerialReader {

    private const val SECTOR_2048 = 2048
    private const val SECTOR_2352 = 2352

    fun extractSerial(file: File): String {
        return try {
            extractInner(file)
        } catch (e: Exception) {
            ""
        }
    }

    private fun extractInner(file: File): String {
        if (!file.isFile) return ""
        val size = try {
            file.length()
        } catch (e: Exception) {
            return ""
        }
        if (size < 64L * 1024L) return ""
        val sectorSize = when {
            size % SECTOR_2352 == 0L -> SECTOR_2352
            size % SECTOR_2048 == 0L -> SECTOR_2048
            else -> return ""
        }
        // Data offset of the 2048 user bytes inside a raw 2352 sector.
        val dataOffsets =
            if (sectorSize == SECTOR_2352) intArrayOf(24, 16) else intArrayOf(0)
        RandomAccessFile(file, "r").use { raf ->
            for (dataOff in dataOffsets) {
                val token = trySectorLayout(raf, sectorSize, dataOff)
                if (token.isNotEmpty()) return token
            }
        }
        return ""
    }

    private fun trySectorLayout(raf: RandomAccessFile, sectorSize: Int, dataOff: Int): String {
        val pvd = ByteArray(2048)
        raf.seek(16L * sectorSize + dataOff)
        if (readFully(raf, pvd) != 2048) return ""
        // "CD001" at PVD offset 1.
        if (!(pvd[0] == 1.toByte() &&
                pvd[1] == 'C'.code.toByte() && pvd[2] == 'D'.code.toByte() &&
                pvd[3] == '0'.code.toByte() && pvd[4] == '0'.code.toByte() &&
                pvd[5] == '1'.code.toByte())
        ) return ""
        val rootExtent = le32(pvd, 156)
        val rootLen = le32(pvd, 160)
        if (rootExtent <= 0 || rootLen <= 0 || rootLen > 256L * 1024L) return ""
        val dirData = readSectors(raf, rootExtent, rootLen, sectorSize, dataOff, 8)
            ?: return ""
        val syscnf = findFile(dirData, raf, "SYSTEM.CNF", sectorSize, dataOff)
            ?: return ""
        val boot2 = parseBoot2(syscnf) ?: return ""
        return normalizeSerial(boot2)
    }

    /** Reads [byteLen] bytes starting at sector [extent], sector by sector. */
    private fun readSectors(
        raf: RandomAccessFile,
        extent: Long,
        byteLen: Long,
        sectorSize: Int,
        dataOff: Int,
        maxSectors: Int
    ): ByteArray? {
        return try {
            val sectors = ((byteLen + 2047) / 2048).toInt().coerceAtMost(maxSectors)
            val out = ByteArray(sectors * 2048)
            for (i in 0 until sectors) {
                raf.seek(extent * sectorSize + dataOff + i.toLong() * sectorSize)
                if (readFully(raf, out, i * 2048, 2048) != 2048) return null
            }
            out.copyOf(byteLen.coerceAtMost(out.size.toLong()).toInt())
        } catch (e: Exception) {
            null
        }
    }

    /** Walks directory records for [name]; returns the file's bytes. */
    private fun findFile(
        dirData: ByteArray,
        raf: RandomAccessFile,
        name: String,
        sectorSize: Int,
        dataOff: Int
    ): ByteArray? {
        var pos = 0
        while (pos + 34 <= dirData.size) {
            val recLen = dirData[pos].toInt() and 0xFF
            if (recLen == 0) {
                // Padding to the next sector.
                pos = ((pos / 2048) + 1) * 2048
                continue
            }
            if (pos + recLen > dirData.size) break
            val nameLen = dirData[pos + 32].toInt() and 0xFF
            if (nameLen > 0 && pos + 33 + nameLen <= pos + recLen &&
                pos + 33 + nameLen <= dirData.size
            ) {
                var entryName = dirData
                    .copyOfRange(pos + 33, pos + 33 + nameLen)
                    .toString(Charsets.US_ASCII)
                entryName = entryName.substringBefore(';')
                if (entryName == name) {
                    val extent = le32(dirData, pos + 2)
                    val len = le32(dirData, pos + 10)
                    if (extent > 0 && len in 1..8192) {
                        return readSectors(raf, extent, len, sectorSize, dataOff, 4)
                    }
                    return null
                }
            }
            pos += recLen
        }
        return null
    }

    /** "BOOT2 = cdrom0:\SLUS_203.12;1" -> "SLUS_203.12;1". */
    private fun parseBoot2(text: ByteArray): String? {
        val lines = text.toString(Charsets.US_ASCII).lines()
        for (raw in lines) {
            val line = raw.trim()
            if (!line.startsWith("BOOT2", ignoreCase = true)) continue
            val eq = line.indexOf('=')
            if (eq < 0) continue
            var value = line.substring(eq + 1).trim()
            value = value.substringAfterLast('\\').substringAfterLast(':').trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    /**
     * "SLUS_203.12;1" -> "SLUS-20312": uppercase, drop ";" and everything
     * after it, '_' becomes '-', dots removed. "" unless it looks like a
     * real serial (letters, dash, digits).
     */
    fun normalizeSerial(token: String): String {
        var s = token.uppercase(Locale.US).substringBefore(';').trim()
        s = s.replace('_', '-').replace(".", "")
        return if (s.matches(Regex("[A-Z]{2,5}-[0-9]{3,6}"))) s else ""
    }

    private fun le32(b: ByteArray, o: Int): Long {
        if (o + 4 > b.size) return 0
        return (b[o].toLong() and 0xFF) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24)
    }

    private fun readFully(raf: RandomAccessFile, buf: ByteArray): Int =
        readFully(raf, buf, 0, buf.size)

    private fun readFully(raf: RandomAccessFile, buf: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = raf.read(buf, off + total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
