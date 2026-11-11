package com.rocketgod.warble.ui

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rocketgod.warble.usb.EspFlasher
import com.rocketgod.warble.usb.KokoFirmware
import com.rocketgod.warble.usb.UsbBeast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Wardrive Lite (SHOK3R fork): ESP32 flasher + USB serial terminal.

private fun espDevices(ctx: Context): List<UsbDevice> =
    (ctx.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values.filter { EspFlasher.isEspCandidate(it) }

/** Stops the scanner's own ESP drivers so a board is free for the flasher / terminal. */
private fun releaseBoards() {
    EspFlasher.active = true
    com.rocketgod.warble.usb.KokoMarauder.abort()
    com.rocketgod.warble.usb.GhostEsp.abort()
    com.rocketgod.warble.usb.FreeWili.abort()
}

private suspend fun ensurePermission(ctx: Context, d: UsbDevice): Boolean {
    if (UsbBeast.hasPermission(ctx, d)) return true
    withContext(Dispatchers.Main) { UsbBeast.requestPermission(ctx, d) }
    repeat(120) { delay(250); if (UsbBeast.hasPermission(ctx, d)) return true }
    return false
}

@Composable
private fun ToolHeader(kicker: String, title: String, accent: Color, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Box(Modifier.size(44.dp).border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(22.dp)).clickable { onBack() },
            contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = accent)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(kicker.uppercase(), color = accent, fontFamily = Mono, fontSize = 12.sp, letterSpacing = 3.sp)
            Text(title, color = Palette.ink, fontFamily = Shok3rHead, fontSize = 30.sp)
        }
    }
}

@Composable
private fun BtCard(accent: Color, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)
        .border(1.dp, Palette.line, RoundedCornerShape(12.dp))
        .background(Palette.panel, RoundedCornerShape(12.dp)).padding(14.dp)) { content() }
}

@Composable
private fun BtLabel(t: String, accent: Color) =
    Text(t.uppercase(), color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp)

@Composable
private fun BtChoice(text: String, sub: String?, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)
        .border(1.dp, if (selected) accent else Palette.line, RoundedCornerShape(10.dp))
        .background(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(10.dp))
        .clickable { onClick() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).background(if (selected) accent else Color.Transparent, RoundedCornerShape(7.dp))
            .border(1.5.dp, accent, RoundedCornerShape(7.dp)))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(text, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (sub != null) Text(sub, color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BtPill(text: String, accent: Color, filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.border(1.dp, if (enabled) accent else Palette.line, RoundedCornerShape(20.dp))
        .background(if (filled && enabled) accent else Color.Transparent, RoundedCornerShape(20.dp))
        .clickable(enabled = enabled) { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, color = if (filled && enabled) inkOn(accent) else if (enabled) Palette.ink else Palette.muted,
            fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun DevicePicker(devices: List<UsbDevice>, selected: UsbDevice?, accent: Color, onPick: (UsbDevice) -> Unit) {
    if (devices.isEmpty()) {
        Text("No ESP board recognized.\nPlug ONE bare ESP32 (e.g. a XIAO C5) straight into this phone with a USB-C OTG data cable — not into the Biscuit host.",
            color = Palette.muted, fontFamily = Mono, fontSize = 13.sp)
        val ctx = LocalContext.current
        val all = remember { (ctx.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values.toList() }
        if (all.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("USB devices this phone sees right now:", color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
            all.forEach { d ->
                Text("• %04x:%04x  %s".format(d.vendorId, d.productId, d.productName ?: d.deviceName),
                    color = Palette.ink, fontFamily = Mono, fontSize = 11.sp)
            }
            Text("If your board isn't listed, it's a cable/OTG issue or it's connected through another board, not the phone.",
                color = Palette.muted, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        } else {
            Text("This phone sees no USB devices at all — check the OTG cable supports data, and that the board is powered.",
                color = Color(0xFFFFB300), fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    } else devices.forEach { d ->
        BtChoice(d.productName ?: "USB device", EspFlasher.describe(d), d == selected, accent) { onPick(d) }
    }
}

@Composable
private fun LogBox(lines: List<String>, accent: Color, modifier: Modifier = Modifier) {
    val st = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) st.scrollToItem(lines.size - 1) }
    LazyColumn(state = st, modifier = modifier.fillMaxWidth()
        .border(1.dp, Palette.line, RoundedCornerShape(10.dp)).background(Palette.paper, RoundedCornerShape(10.dp))
        .padding(10.dp)) {
        items(lines) { l ->
            Text(l, color = when {
                l.startsWith("✖") -> Palette.danger
                l.startsWith("✓") || l.startsWith("Done") -> accent
                l.startsWith("> ") -> Palette.gold
                else -> Palette.ink
            }, fontFamily = Mono, fontSize = 12.sp)
        }
    }
}

// ============================================================================ Flasher

@Composable
fun FlasherScreen(accent: Color, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }
    DisposableEffect(Unit) { releaseBoards(); onDispose { EspFlasher.active = false } }

    var devices by remember { mutableStateOf(listOf<UsbDevice>()) }
    var selected by remember { mutableStateOf<UsbDevice?>(null) }
    var source by remember { mutableIntStateOf(0) }       // 0 online family, 1 bundled Koko, 2 custom file
    var familyIdx by remember { mutableIntStateOf(0) }    // index into KokoFirmware.CATALOG (0 = Marauder, streams to phone)
    var targetIdx by remember { mutableIntStateOf(0) }
    var fullInstall by remember { mutableStateOf(true) }
    var release by remember { mutableStateOf<KokoFirmware.Release?>(null) }
    var releaseNote by remember { mutableStateOf("Checking GitHub for the latest release…") }
    var customUri by remember { mutableStateOf<Uri?>(null) }
    var customName by remember { mutableStateOf("") }
    var customOffset by remember { mutableStateOf("0x0") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val log = remember { mutableStateListOf<String>() }

    fun say(s: String) { scope.launch(Dispatchers.Main) { log.add(s); if (log.size > 300) log.removeAt(0) } }

    LaunchedEffect(Unit) {
        while (true) {
            val now = espDevices(ctx)
            devices = now
            if (selected == null || selected !in now) selected = now.firstOrNull()
            delay(1500)
        }
    }
    LaunchedEffect(familyIdx) {
        release = null; targetIdx = 0
        val fam = KokoFirmware.CATALOG[familyIdx]
        releaseNote = "Checking GitHub for the latest ${fam.name}…"
        val r = withContext(Dispatchers.IO) { runCatching { KokoFirmware.latest(fam.repo) } }
        r.onSuccess { release = it; releaseNote = "Latest: ${it.version}" }
            .onFailure { releaseNote = it.message ?: "Couldn't reach GitHub" }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { customUri = uri; customName = uri.lastPathSegment?.substringAfterLast('/') ?: "firmware.bin"; source = 2 }
    }

    Column(Modifier.fillMaxSize().background(Palette.paper).statusBarsPadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ToolHeader("ESP32 · USB", "Flash a board", accent, onBack)

        BtCard(accent) {
            BtLabel("1 · Board", accent)
            Spacer(Modifier.height(8.dp))
            DevicePicker(devices, selected, accent) { selected = it }
            Spacer(Modifier.height(6.dp))
            Text("Board not answering? Hold BOOT, tap RESET (or replug while holding BOOT), then flash.",
                color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
        }

        BtCard(accent) {
            BtLabel("2 · Firmware", accent)
            Spacer(Modifier.height(8.dp))
            // Firmware family — pick the one that matches how you want GPS logged.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KokoFirmware.CATALOG.forEachIndexed { i, f ->
                    BtPill(f.name + if (f.streamsToPhone) " ★" else "", accent, filled = source == 0 && familyIdx == i) { familyIdx = i; source = 0 }
                }
            }
            val fam = KokoFirmware.CATALOG[familyIdx]
            Text((if (fam.streamsToPhone) "Phone GPS: " else "On-board GPS: ") + fam.note,
                color = if (fam.streamsToPhone) Palette.gold else Color(0xFFFFB300), fontFamily = Mono, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(8.dp))
            BtChoice("${fam.name} · latest", releaseNote, source == 0, accent) { source = 0 }
            val rel = release
            if (source == 0 && rel != null) {
                Spacer(Modifier.height(4.dp))
                Text("Pick your exact board:", color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rel.targets.forEachIndexed { i, t -> BtPill(t.name, accent, filled = i == targetIdx) { targetIdx = i } }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BtPill("Full install", accent, filled = fullInstall) { fullInstall = true }
                    BtPill("Update app only", accent, filled = !fullInstall) { fullInstall = false }
                }
                Text(if (fullInstall) "Writes bootloader, partitions and app." else "Writes only the app; keeps settings.",
                    color = Palette.muted, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(6.dp))
            BtChoice("Koko Dual-Band Wardriver · ${KokoFirmware.BUNDLED_VERSION} (offline, standalone)",
                "Built into this app · needs its own GPS + SD · ignores the phone", source == 1, accent) { source = 1 }
            BtChoice(if (customName.isEmpty()) "Pick a .bin file…" else "File: $customName",
                "Any ESP32 image you trust (e.g. a merged .bin)", source == 2, accent) { picker.launch(arrayOf("*/*")) }
            if (source == 2) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text("Offset ", color = Palette.muted, fontFamily = Mono, fontSize = 13.sp)
                    BasicTextField(customOffset, { customOffset = it.trim() }, singleLine = true,
                        textStyle = TextStyle(color = Palette.ink, fontFamily = Mono, fontSize = 14.sp),
                        cursorBrush = SolidColor(accent),
                        modifier = Modifier.width(120.dp).border(1.dp, Palette.line, RoundedCornerShape(8.dp)).padding(8.dp))
                    Text("  merged images: 0x0", color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("★ = streams detections to this phone, so the phone's GPS tags and logs them into the same CSV you upload — " +
                "no GPS module needed on the board. Standalone firmware logs on its own SD card and needs its own GPS. " +
                "Firmware is built for specific C5 boards; on a different C5 (like a bare XIAO) it runs but the GPS / SD / screen pins may not match.",
                color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
        }

        BtCard(accent) {
            BtLabel("3 · Flash", accent)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().background(if (busy || selected == null) Palette.line else accent, RoundedCornerShape(12.dp))
                .clickable(enabled = !busy && selected != null) {
                    busy = true; progress = 0f; log.clear()
                    scope.launch(Dispatchers.IO) {
                        try {
                            val d = selected ?: throw EspFlasher.FlashError("Plug in a board first")
                            say("Asking for USB permission…")
                            if (!ensurePermission(ctx, d)) throw EspFlasher.FlashError("USB permission was not granted")
                            delay(400) // let the scanner's drivers let go of the port
                            var size = 4 * 1024 * 1024
                            var chip: Int? = EspFlasher.CHIP_ID_ESP32C5
                            val segs: MutableList<EspFlasher.Segment> = when (source) {
                                0 -> {
                                    val r = release ?: throw EspFlasher.FlashError("No release loaded")
                                    val t = r.targets[targetIdx.coerceIn(0, r.targets.size - 1)]
                                    size = t.flashSize
                                    chip = t.expectChipId
                                    say("${KokoFirmware.CATALOG[familyIdx].name} ${r.version} · ${t.name} · ${if (fullInstall) "full install" else "app update"}")
                                    KokoFirmware.fetch(r, if (fullInstall) t.factory else t.update) { say(it) }.toMutableList()
                                }
                                1 -> KokoFirmware.bundled(ctx).toMutableList()
                                else -> {
                                    val u = customUri ?: throw EspFlasher.FlashError("Pick a firmware file first")
                                    val off = customOffset.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
                                        ?: throw EspFlasher.FlashError("Offset must be hex, like 0x0 or 0x10000")
                                    val bytes = ctx.contentResolver.openInputStream(u)?.use { it.readBytes() }
                                        ?: throw EspFlasher.FlashError("Couldn't read the file")
                                    chip = null
                                    mutableListOf(EspFlasher.Segment(off, bytes, customName))
                                }
                            }
                            if (source == 1 || (source == 0 && fullInstall)) KokoFirmware.otadataWipe(segs)?.let { segs.add(it) }
                            EspFlasher.flash(ctx, d, segs.sortedBy { it.offset }, size, chip, { say(it) }) { progress = it }
                        } catch (e: Exception) {
                            say("✖ " + (e.message ?: e.javaClass.simpleName))
                        } finally {
                            withContext(Dispatchers.Main) { busy = false }
                        }
                    }
                }.padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                Text(if (busy) "FLASHING… ${(progress * 100).toInt()}%" else "⚡ FLASH BOARD",
                    color = if (busy || selected == null) Palette.muted else inkOn(accent), fontFamily = Shok3rHead, fontSize = 22.sp)
            }
            if (busy || progress > 0f) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { progress }, color = accent, trackColor = Palette.line, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(10.dp))
            LogBox(log, accent, Modifier.heightIn(min = 80.dp, max = 260.dp))
            Text("Keep the phone awake and the cable still until it says Done.",
                color = Palette.muted, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ============================================================================ Terminal

@Composable
fun TerminalScreen(accent: Color, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }

    var devices by remember { mutableStateOf(listOf<UsbDevice>()) }
    var selected by remember { mutableStateOf<UsbDevice?>(null) }
    var port by remember { mutableStateOf<EspFlasher.Port?>(null) }
    var input by remember { mutableStateOf("") }
    val log = remember { mutableStateListOf<String>() }
    val running = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    fun say(s: String) { scope.launch(Dispatchers.Main) { log.add(s); if (log.size > 600) log.removeAt(0) } }
    fun disconnect() {
        running.set(false)
        port?.close(); port = null
        EspFlasher.active = false
    }
    DisposableEffect(Unit) { onDispose { disconnect() } }

    LaunchedEffect(Unit) {
        while (true) {
            val now = espDevices(ctx)
            devices = now
            if (selected == null || selected !in now) selected = now.firstOrNull()
            if (port != null && selected == null) { disconnect(); say("✖ Board unplugged") }
            delay(1500)
        }
    }

    fun connect() {
        val d = selected ?: return
        releaseBoards()
        scope.launch(Dispatchers.IO) {
            if (!ensurePermission(ctx, d)) { say("✖ USB permission was not granted"); EspFlasher.active = false; return@launch }
            delay(400)
            val conn = (ctx.getSystemService(Context.USB_SERVICE) as UsbManager).openDevice(d)
            if (conn == null) { say("✖ Couldn't open the board"); EspFlasher.active = false; return@launch }
            val p = EspFlasher.Port(conn, d)
            if (!p.open(115200)) { p.close(); say("✖ No serial interface on this device"); EspFlasher.active = false; return@launch }
            // Idle lines: native USB likes DTR set; on UART bridges DTR drives IO0, so leave it off.
            p.setLines(dtr = p.isUsbJtag, rts = false)
            withContext(Dispatchers.Main) { port = p }
            say("✓ Connected · 115200 baud")
            running.set(true)
            val buf = ByteArray(4096)
            val line = StringBuilder()
            while (running.get()) {
                val n = runCatching { p.read(buf, 200) }.getOrDefault(-1)
                if (n <= 0) continue
                for (ch in String(buf, 0, n, Charsets.UTF_8)) {
                    when (ch) {
                        '\n' -> { say(line.toString().trimEnd('\r')); line.setLength(0) }
                        else -> if (line.length < 400) line.append(ch)
                    }
                }
            }
        }
    }

    fun send(cmd: String) {
        val p = port ?: return
        if (cmd.isBlank()) return
        say("> $cmd")
        scope.launch(Dispatchers.IO) { if (!p.write((cmd + "\r\n").toByteArray())) say("✖ Write failed") }
    }

    Column(Modifier.fillMaxSize().background(Palette.paper).statusBarsPadding().navigationBarsPadding().imePadding()
        .padding(horizontal = 16.dp)) {
        ToolHeader("USB board", "Terminal", accent, onBack)
        DevicePicker(devices, selected, accent) { if (port == null) selected = it }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            if (port == null) BtPill("Connect", accent, filled = true, enabled = selected != null) { connect() }
            else BtPill("Disconnect", accent, filled = true) { disconnect(); say("Disconnected") }
            BtPill("Reset board", accent, enabled = port != null) {
                val p = port ?: return@BtPill
                scope.launch(Dispatchers.IO) {
                    // Pulse EN with IO0 high = normal boot (not download mode)
                    p.setLines(dtr = false, rts = true); delay(150); p.setLines(dtr = false, rts = false)
                    if (p.isUsbJtag) say("Board reset · native USB re-connects, tap Connect again if it goes quiet")
                    else { p.setLines(dtr = false, rts = false); say("Board reset") }
                }
            }
            BtPill("Clear", accent) { log.clear() }
        }
        Spacer(Modifier.height(8.dp))
        LogBox(log, accent, Modifier.weight(1f))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            for (c in listOf("help", "info", "scanap", "stopscan", "list -a", "gpsdata")) BtPill(c, accent, enabled = port != null) { send(c) }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            BasicTextField(input, { input = it }, singleLine = true,
                textStyle = TextStyle(color = Palette.ink, fontFamily = Mono, fontSize = 15.sp),
                cursorBrush = SolidColor(accent),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (input.isEmpty()) Text("Command for the board", color = Palette.muted, fontFamily = Mono, fontSize = 15.sp)
                        inner()
                    }
                },
                modifier = Modifier.weight(1f).border(1.dp, Palette.line, RoundedCornerShape(10.dp)).padding(14.dp))
            Spacer(Modifier.width(8.dp))
            BtPill("Send", accent, filled = true, enabled = port != null) { send(input); input = "" }
        }
    }
}
