package com.aeonps2.firetv

import java.io.File
import java.io.FileInputStream

/**
 * Trust-but-verify for PlayStation 2 BIOS files.
 *
 * Hard rule (kept by callers): warn when the size is not exactly 4 MB
 * (4,194,304 bytes). Content checks are heuristic, and the first bytes
 * must not be all zeroes — so [check] never hard-blocks; callers show a
 * warning dialog instead and let the user import the file anyway.
 */
object BiosCheck {
    const val BIOS_SIZE = 4194304L
    private const val HEAD_SIZE = 4096
    private const val MIN_NON_ZERO = 100

    /** GENUINE: looks like a real Sony BIOS. SUSPICIOUS: right size, odd bytes. INVALID: clearly not a BIOS. */
    enum class Verdict { GENUINE, SUSPICIOUS, INVALID }

    fun check(file: File): Verdict {
        if (!file.isFile || file.length() != BIOS_SIZE) return Verdict.INVALID
        val head = readHead(file)
        if (head.isEmpty()) return Verdict.INVALID

        // Genuine Sony dumps carry readable markers near the start.
        val text = head.copyOf(minOf(2048, head.size)).toString(Charsets.US_ASCII)
        if (text.contains("Sony Computer Entertainment") ||
            text.contains("PlayStation 2")
        ) {
            return Verdict.GENUINE
        }

        // Magic signatures for files that are definitely not a BIOS.
        val isZip = matches(head, 0x50, 0x4B)                       // "PK"
        val isRar = matches(head, 0x52, 0x61, 0x72, 0x21)           // "Rar!"
        val isExe = matches(head, 0x4D, 0x5A)                       // "MZ"

        val nonZero = head.count { it != 0.toByte() }
        val printable = head.count { b ->
            val v = b.toInt() and 0xFF
            v in 32..126 || v == 10 || v == 13 || v == 9
        }
        val mostlyText = printable * 100 > head.size * 90

        if (nonZero < MIN_NON_ZERO) return Verdict.INVALID   // all-zeroes dummy
        if (isZip || isRar || isExe || mostlyText) return Verdict.INVALID
        return Verdict.SUSPICIOUS
    }

    private fun matches(head: ByteArray, vararg bytes: Int): Boolean {
        if (head.size < bytes.size) return false
        for (i in bytes.indices) {
            if ((head[i].toInt() and 0xFF) != bytes[i]) return false
        }
        return true
    }

    private fun readHead(file: File): ByteArray {
        return try {
            FileInputStream(file).use { ins ->
                val buf = ByteArray(HEAD_SIZE)
                var total = 0
                while (total < HEAD_SIZE) {
                    val n = ins.read(buf, total, HEAD_SIZE - total)
                    if (n < 0) break
                    total += n
                }
                buf.copyOf(total)
            }
        } catch (e: Exception) {
            ByteArray(0)
        }
    }
}
