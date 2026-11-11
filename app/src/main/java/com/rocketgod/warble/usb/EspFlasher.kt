package com.rocketgod.warble.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Wardrive Lite (SHOK3R fork): flashes ESP32 firmware over USB OTG using the chip's
 * built-in ROM serial bootloader (the same protocol esptool.py speaks). No stub loader:
 * plain FLASH_BEGIN / FLASH_DATA blocks, then an MD5 read-back to verify each segment.
 *
 * Works with the native USB-Serial/JTAG port (Espressif 303a:1001, e.g. ESP32-C5 / XIAO C5)
 * and with CDC-ACM or CP210x USB-UART bridges.
 */
object EspFlasher {

    /** True while the flasher owns the USB port, so the scanner doesn't grab the board. */
    @Volatile var active = false

    class FlashError(msg: String) : Exception(msg)

    data class Segment(val offset: Int, val data: ByteArray, val name: String)

    const val CHIP_ID_ESP32C5 = 23

    fun isEspCandidate(d: UsbDevice): Boolean =
        d.vendorId == 0x303a || d.vendorId == 0x10c4 || d.vendorId == 0x1a86 || hasCdc(d)

    fun describe(d: UsbDevice): String {
        val kind = when (d.vendorId) {
            0x303a -> "native USB (Espressif)"
            0x10c4 -> "CP210x bridge"
            0x1a86 -> if (hasCdc(d)) "CH34x bridge (CDC)" else "CH340 bridge — not supported, use the board's native USB port"
            else -> if (hasCdc(d)) "USB serial (CDC)" else "unknown"
        }
        val name = d.productName ?: d.deviceName
        return "%s · %04x:%04x · %s".format(name, d.vendorId, d.productId, kind)
    }

    private fun hasCdc(d: UsbDevice): Boolean =
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }

    // ------------------------------------------------------------------ USB port

    class Port(private val conn: UsbDeviceConnection, private val dev: UsbDevice) {
        val isUsbJtag = dev.vendorId == 0x303a
        private val isCp210x = dev.vendorId == 0x10c4
        private var epIn: UsbEndpoint? = null
        private var epOut: UsbEndpoint? = null
        private var comm: UsbInterface? = null
        private var data: UsbInterface? = null

        fun open(baud: Int): Boolean {
            if (isCp210x) {
                val intf = dev.getInterface(0)
                data = intf; comm = intf
                if (!conn.claimInterface(intf, true)) return false
                pickEndpoints(intf)
                conn.controlTransfer(0x41, 0x00, 0x0001, intf.id, null, 0, 1000) // IFC_ENABLE
                conn.controlTransfer(0x41, 0x03, 0x0800, intf.id, null, 0, 1000) // 8N1
                setBaud(baud)
            } else {
                for (i in 0 until dev.interfaceCount) {
                    val intf = dev.getInterface(i)
                    when (intf.interfaceClass) {
                        UsbConstants.USB_CLASS_COMM -> if (comm == null) comm = intf
                        UsbConstants.USB_CLASS_CDC_DATA -> if (data == null) data = intf
                    }
                }
                val d = data ?: return false
                comm?.let { conn.claimInterface(it, true) }
                if (!conn.claimInterface(d, true)) return false
                pickEndpoints(d)
                setBaud(baud)
            }
            return epIn != null && epOut != null
        }

        private fun pickEndpoints(intf: UsbInterface) {
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
        }

        fun setBaud(baud: Int) {
            val b = byteArrayOf((baud and 0xFF).toByte(), ((baud shr 8) and 0xFF).toByte(),
                ((baud shr 16) and 0xFF).toByte(), ((baud shr 24) and 0xFF).toByte())
            if (isCp210x) {
                conn.controlTransfer(0x41, 0x1E, 0, data!!.id, b, 4, 1000)
            } else {
                val lc = b + byteArrayOf(0, 0, 8)
                conn.controlTransfer(0x21, 0x20, 0, (comm ?: data)!!.id, lc, lc.size, 1000)
            }
        }

        /** DTR / RTS, as esptool drives them. */
        fun setLines(dtr: Boolean, rts: Boolean) {
            if (isCp210x) {
                var v = 0x0300
                if (dtr) v = v or 0x01
                if (rts) v = v or 0x02
                conn.controlTransfer(0x41, 0x07, v, data!!.id, null, 0, 1000)
            } else {
                var v = 0
                if (dtr) v = v or 0x01
                if (rts) v = v or 0x02
                conn.controlTransfer(0x21, 0x22, v, (comm ?: data)!!.id, null, 0, 1000)
            }
        }

        fun write(b: ByteArray): Boolean {
            var off = 0
            while (off < b.size) {
                val n = conn.bulkTransfer(epOut!!, b, off, minOf(16384, b.size - off), 2000)
                if (n <= 0) return false
                off += n
            }
            return true
        }

        fun read(buf: ByteArray, timeoutMs: Int): Int = conn.bulkTransfer(epIn!!, buf, buf.size, timeoutMs)

        fun close() {
            runCatching { comm?.let { conn.releaseInterface(it) } }
            runCatching { data?.let { if (it != comm) conn.releaseInterface(it) } }
            runCatching { conn.close() }
        }
    }

    // ------------------------------------------------------------------ ROM protocol

    private const val OP_FLASH_BEGIN = 0x02
    private const val OP_FLASH_DATA = 0x03
    private const val OP_FLASH_END = 0x04
    private const val OP_SYNC = 0x08
    private const val OP_SPI_SET_PARAMS = 0x0B
    private const val OP_SPI_ATTACH = 0x0D
    private const val OP_CHANGE_BAUD = 0x0F
    private const val OP_SPI_FLASH_MD5 = 0x13
    private const val OP_GET_SECURITY_INFO = 0x14
    private const val BLOCK = 0x400          // ROM flash write block size
    private const val STATUS_LEN = 4         // ESP32 and later ROMs return 4 status bytes

    private class Rom(val port: Port, val log: (String) -> Unit) {
        private val tmp = ByteArray(16384)
        private val frame = ByteArrayOutputStream()
        private val frames = ArrayDeque<ByteArray>()
        private var inFrame = false
        private var esc = false
        private var deadReads = 0

        private fun pump(timeoutMs: Int) {
            val n = port.read(tmp, timeoutMs)
            if (n < 0) { deadReads++; return }
            deadReads = 0
            for (i in 0 until n) {
                val b = tmp[i].toInt() and 0xFF
                if (!inFrame) { if (b == 0xC0) { inFrame = true; frame.reset() }; continue }
                if (esc) {
                    esc = false
                    frame.write(when (b) { 0xDC -> 0xC0; 0xDD -> 0xDB; else -> b })
                    continue
                }
                when (b) {
                    0xDB -> esc = true
                    0xC0 -> if (frame.size() == 0) { /* back-to-back delimiter: new frame */ }
                            else { frames.addLast(frame.toByteArray()); frame.reset(); inFrame = false }
                    else -> frame.write(b)
                }
            }
        }

        fun flushInput() {
            frames.clear(); frame.reset(); inFrame = false; esc = false
            repeat(5) { if (port.read(tmp, 30) <= 0) return }
        }

        private fun slip(p: ByteArray): ByteArray {
            val o = ByteArrayOutputStream(p.size + 16)
            o.write(0xC0)
            for (x in p) {
                when (val b = x.toInt() and 0xFF) {
                    0xC0 -> { o.write(0xDB); o.write(0xDC) }
                    0xDB -> { o.write(0xDB); o.write(0xDD) }
                    else -> o.write(b)
                }
            }
            o.write(0xC0)
            return o.toByteArray()
        }

        /** Sends a command and returns (value, data-without-status). Throws on error status. */
        fun command(op: Int, data: ByteArray, chk: Int = 0, timeoutMs: Int = 3000, check: Boolean = true): Pair<Long, ByteArray> {
            val pkt = ByteArray(8 + data.size)
            pkt[0] = 0; pkt[1] = op.toByte()
            pkt[2] = (data.size and 0xFF).toByte(); pkt[3] = ((data.size shr 8) and 0xFF).toByte()
            putU32(pkt, 4, chk.toLong())
            System.arraycopy(data, 0, pkt, 8, data.size)
            if (!port.write(slip(pkt))) throw FlashError("USB write failed — is the board still connected?")
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val f = frames.removeFirstOrNull()
                if (f == null) {
                    pump(minOf(100, (end - System.currentTimeMillis()).toInt().coerceAtLeast(1)))
                    if (deadReads > 20) throw FlashError("Lost the USB connection")
                    continue
                }
                if (f.size < 8 || f[0].toInt() != 1 || (f[1].toInt() and 0xFF) != op) continue
                val value = u32(f, 4)
                val body = f.copyOfRange(8, f.size)
                if (!check) return value to body
                if (body.size < STATUS_LEN) throw FlashError("Short reply to command 0x%02x".format(op))
                val st = body[body.size - STATUS_LEN].toInt() and 0xFF
                val err = body[body.size - STATUS_LEN + 1].toInt() and 0xFF
                if (st != 0) throw FlashError("Bootloader refused command 0x%02x (error 0x%02x)".format(op, err))
                return value to body.copyOfRange(0, body.size - STATUS_LEN)
            }
            throw FlashError("Timed out waiting for the bootloader (command 0x%02x)".format(op))
        }

        fun sync(): Boolean {
            val d = ByteArray(36)
            d[0] = 0x07; d[1] = 0x07; d[2] = 0x12; d[3] = 0x20
            for (i in 4 until 36) d[i] = 0x55
            for (attempt in 0 until 6) {
                try {
                    command(OP_SYNC, d, timeoutMs = 150, check = false)
                    Thread.sleep(50)
                    flushInput()
                    return true
                } catch (e: FlashError) {
                    if (e.message?.startsWith("Lost") == true || e.message?.startsWith("USB write") == true) throw e
                }
            }
            return false
        }

        fun chipId(): Int? = runCatching {
            val (_, d) = command(OP_GET_SECURITY_INFO, ByteArray(0), timeoutMs = 1000)
            if (d.size >= 16) u32(d, 12).toInt() else null
        }.getOrNull()

        fun attachAndSetSize(sizeBytes: Int) {
            command(OP_SPI_ATTACH, ByteArray(8))
            val p = ByteArray(24)
            putU32(p, 0, 0); putU32(p, 4, sizeBytes.toLong()); putU32(p, 8, 0x10000)
            putU32(p, 12, 0x1000); putU32(p, 16, 0x100); putU32(p, 20, 0xFFFF)
            command(OP_SPI_SET_PARAMS, p)
        }

        fun changeBaud(newBaud: Int) {
            val p = ByteArray(8); putU32(p, 0, newBaud.toLong()); putU32(p, 4, 0)
            command(OP_CHANGE_BAUD, p)
            port.setBaud(newBaud)
            Thread.sleep(60)
            flushInput()
        }

        fun writeSegment(seg: Segment, progress: (Int, Int) -> Unit) {
            val total = seg.data.size
            val blocks = (total + BLOCK - 1) / BLOCK
            val begin = ByteArray(20)
            putU32(begin, 0, total.toLong()); putU32(begin, 4, blocks.toLong())
            putU32(begin, 8, BLOCK.toLong()); putU32(begin, 12, seg.offset.toLong()); putU32(begin, 16, 0)
            val eraseMs = maxOf(5000, (30_000L * total / (1024 * 1024)).toInt() + 3000)
            log("Erasing ${total / 1024} KB at 0x%x…".format(seg.offset))
            command(OP_FLASH_BEGIN, begin, timeoutMs = eraseMs)
            for (seq in 0 until blocks) {
                val chunk = ByteArray(BLOCK) { 0xFF.toByte() }
                val off = seq * BLOCK
                System.arraycopy(seg.data, off, chunk, 0, minOf(BLOCK, total - off))
                val pkt = ByteArray(16 + BLOCK)
                putU32(pkt, 0, BLOCK.toLong()); putU32(pkt, 4, seq.toLong())
                System.arraycopy(chunk, 0, pkt, 16, BLOCK)
                var chk = 0xEF
                for (b in chunk) chk = chk xor (b.toInt() and 0xFF)
                var tries = 0
                while (true) {
                    try { command(OP_FLASH_DATA, pkt, chk = chk, timeoutMs = 5000); break }
                    catch (e: FlashError) {
                        if (++tries >= 3) throw e
                        log("Block $seq retry $tries: ${e.message}")
                        flushInput()
                    }
                }
                progress(minOf(total, off + BLOCK), total)
            }
        }

        fun md5(offset: Int, size: Int): String {
            val p = ByteArray(16)
            putU32(p, 0, offset.toLong()); putU32(p, 4, size.toLong())
            val (_, d) = command(OP_SPI_FLASH_MD5, p, timeoutMs = maxOf(5000, (8000L * size / (1024 * 1024)).toInt() + 3000))
            return when (d.size) {
                32 -> String(d, Charsets.US_ASCII).lowercase()
                16 -> d.joinToString("") { "%02x".format(it) }
                else -> throw FlashError("Unexpected MD5 reply (${d.size} bytes)")
            }
        }

        fun leave() {
            runCatching { command(OP_FLASH_BEGIN, ByteArray(20), timeoutMs = 3000) }
            runCatching { val p = ByteArray(4); putU32(p, 0, 1); command(OP_FLASH_END, p, timeoutMs = 2000) }
        }
    }

    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun putU32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v shr 8) and 0xFF).toByte()
        b[o + 2] = ((v shr 16) and 0xFF).toByte(); b[o + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun enterBootloader(port: Port) {
        if (port.isUsbJtag) {
            // esptool's USB-Serial/JTAG sequence
            port.setLines(dtr = false, rts = false); Thread.sleep(100)
            port.setLines(dtr = true, rts = false); Thread.sleep(100)
            port.setLines(dtr = true, rts = true)
            port.setLines(dtr = false, rts = true); Thread.sleep(100)
            port.setLines(dtr = false, rts = false)
        } else {
            // classic DTR -> IO0 / RTS -> EN auto-reset circuit
            port.setLines(dtr = false, rts = true); Thread.sleep(100)
            port.setLines(dtr = true, rts = false); Thread.sleep(50)
            port.setLines(dtr = false, rts = false)
        }
        Thread.sleep(250)
    }

    private fun hardReset(port: Port) {
        port.setLines(dtr = false, rts = true); Thread.sleep(150)
        port.setLines(dtr = false, rts = false)
    }

    fun md5Hex(b: ByteArray, off: Int = 0, len: Int = b.size): String =
        MessageDigest.getInstance("MD5").apply { update(b, off, len) }.digest().joinToString("") { "%02x".format(it) }

    /**
     * Flashes [segments] to the board on [device]. Blocking: call from a background thread.
     * [expectChip] = required chip ID (e.g. [CHIP_ID_ESP32C5]) or null to skip the check.
     */
    fun flash(
        ctx: Context,
        device: UsbDevice,
        segments: List<Segment>,
        flashSize: Int,
        expectChip: Int?,
        log: (String) -> Unit,
        progress: (Float) -> Unit,
    ) {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(device) ?: throw FlashError("Couldn't open the USB device (permission?)")
        val port = Port(conn, device)
        try {
            if (!port.open(115200)) throw FlashError("Couldn't open a serial interface on this device")
            val rom = Rom(port, log)
            rom.flushInput()
            log("Connecting to the ROM bootloader…")
            var synced = rom.sync()   // already in download mode (BOOT held)?
            if (!synced) {
                log("Resetting the board into download mode…")
                enterBootloader(port)
                rom.flushInput()
                synced = rom.sync() || run { enterBootloader(port); rom.flushInput(); rom.sync() }
            }
            if (!synced) throw FlashError("No answer from the bootloader. Hold BOOT, tap RESET (or replug while holding BOOT), then try again.")
            log("Bootloader connected.")

            val chip = rom.chipId()
            if (chip != null) log("Chip ID $chip" + if (chip == CHIP_ID_ESP32C5) " (ESP32-C5)" else "")
            if (expectChip != null && chip != null && chip != expectChip)
                throw FlashError("Wrong chip: this firmware is for ESP32-C5 (ID $expectChip) but the board reports ID $chip")

            if (!port.isUsbJtag) runCatching { rom.changeBaud(460800); log("Switched to 460800 baud") }
                .onFailure { log("Staying at 115200 baud") }

            rom.attachAndSetSize(flashSize)

            val total = segments.sumOf { it.data.size }.toFloat()
            var done = 0
            for (seg in segments) {
                log("Writing ${seg.name} (${seg.data.size / 1024} KB) → 0x%x".format(seg.offset))
                rom.writeSegment(seg) { written, _ -> progress((done + written) / total) }
                done += seg.data.size
                val want = md5Hex(seg.data)
                val got = rom.md5(seg.offset, seg.data.size)
                if (want != got) throw FlashError("Verify failed for ${seg.name}: flash MD5 $got ≠ file $want")
                log("✓ ${seg.name} verified")
            }
            rom.leave()
            log("Rebooting the board…")
            hardReset(port)
            progress(1f)
            log("Done! Firmware installed.")
        } finally {
            port.close()
        }
    }
}
