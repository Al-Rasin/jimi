package com.alrasin.jarvis.actions

import android.content.Context
import android.hardware.ConsumerIrManager
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.core.launch
import kotlinx.coroutines.delay

/**
 * TV / AC remote by the phone's IR blaster (ROADMAP #39, §I.15). TV: known codes for Samsung, LG and Sony are
 * sent one after the other (only the matching TV reacts), so no setup is needed. AC codes are long and
 * per-model, so AC opens the Mi Remote app instead.
 */
object IrCodes {
    const val POWER = "power"
    const val VOL_UP = "volume_up"
    const val VOL_DOWN = "volume_down"
    const val MUTE = "mute"
    const val CH_UP = "channel_up"
    const val CH_DOWN = "channel_down"
    const val INPUT = "input"
    val BUTTONS = listOf(POWER, VOL_UP, VOL_DOWN, MUTE, CH_UP, CH_DOWN, INPUT)
    val BRANDS = listOf("samsung", "lg", "sony")

    /** One IR burst: carrier frequency (Hz) and on/off durations in microseconds, starting with "on". */
    data class Frame(val freq: Int, val pattern: IntArray)

    private val SAMSUNG = mapOf(POWER to 0x02, VOL_UP to 0x07, VOL_DOWN to 0x0B, MUTE to 0x0F, CH_UP to 0x12, CH_DOWN to 0x10, INPUT to 0x01)
    private val LG = mapOf(POWER to 0x08, VOL_UP to 0x02, VOL_DOWN to 0x03, MUTE to 0x09, CH_UP to 0x00, CH_DOWN to 0x01, INPUT to 0x0B)
    private val SONY = mapOf(POWER to 21, VOL_UP to 18, VOL_DOWN to 19, MUTE to 20, CH_UP to 16, CH_DOWN to 17, INPUT to 37)

    /** Frames for [button] on [brand] (null brand = every known brand), or empty when unknown. */
    fun frames(button: String, brand: String? = null): List<Frame> = (brand?.let(::listOf) ?: BRANDS).mapNotNull { b ->
        when (b) {
            "samsung" -> SAMSUNG[button]?.let { nec(0x07, 0x07, it, header = 4500) }
            "lg" -> LG[button]?.let { nec(0x04, 0x04.inv() and 0xFF, it, header = 9000) }
            "sony" -> SONY[button]?.let { sony(1, it) }
            else -> null
        }
    }

    /** NEC-style frame (Samsung32 uses a 4500 µs header and repeats the address). Bits LSB first. */
    fun nec(addr: Int, addr2: Int, cmd: Int, header: Int): Frame {
        val p = mutableListOf(header, 4500)
        for (byte in listOf(addr, addr2, cmd, cmd.inv() and 0xFF)) {
            for (i in 0 until 8) p += listOf(560, if ((byte shr i) and 1 == 1) 1690 else 560)
        }
        p += 560
        return Frame(38_000, p.toIntArray())
    }

    /** Sony SIRC 12-bit: 7 command bits then 5 address bits, LSB first, sent 3 times (TVs ignore a single one). */
    fun sony(addr: Int, cmd: Int): Frame {
        val one = mutableListOf(2400, 600)
        val bits = (0 until 7).map { (cmd shr it) and 1 } + (0 until 5).map { (addr shr it) and 1 }
        bits.forEach { one += listOf(if (it == 1) 1200 else 600, 600) }
        val gap = 45_000 - one.sum()
        val p = mutableListOf<Int>()
        repeat(3) { i -> p += one; if (i < 2) p[p.lastIndex] += gap }
        p.removeAt(p.lastIndex)   // pattern ends on an "on" pulse
        return Frame(40_000, p.toIntArray())
    }
}

private const val MI_REMOTE = "com.duokan.phone.remotecontroller"
private const val GAP_MS = 60L

fun irTool(context: Context): Tool = SimpleTool(
    "ir_remote",
    "Control the TV or AC with the phone's IR blaster (TV power, volume, mute, channel, input)",
    listOf(
        arg("device", "What to control", choices = listOf("tv", "ac")),
        arg("button", "Button to press", required = false, choices = IrCodes.BUTTONS),
        arg("times", "How many presses (volume)", required = false),
    ),
) { call, _ ->
    val device = call.arg("device")?.lowercase() ?: "tv"
    val ir = context.getSystemService(ConsumerIrManager::class.java)?.takeIf { it.hasIrEmitter() }
    if (device != "tv" || ir == null) {
        val open = context.packageManager.getLaunchIntentForPackage(MI_REMOTE)?.let(context::launch) == true
        val why = if (ir == null) "This phone has no IR blaster I can use." else "AC codes differ per model."
        return@SimpleTool if (open) ToolResult.ok("$why Opening Mi Remote.") else ToolResult.fail("$why Install Mi Remote to control the ${device.uppercase()}.")
    }
    val button = call.arg("button")?.lowercase()?.takeIf { it in IrCodes.BUTTONS } ?: IrCodes.POWER
    val times = call.arg("times")?.toIntOrNull()?.coerceIn(1, 10) ?: 1
    val frames = IrCodes.frames(button)
    repeat(times) { n ->
        frames.forEach { f ->
            runCatching { ir.transmit(f.freq, f.pattern) }.onFailure { JarvisLog.e("ir transmit failed", it) }
            delay(GAP_MS)
        }
        if (n < times - 1) delay(GAP_MS)
    }
    ToolResult.ok("Sent ${button.replace('_', ' ')} to the TV.")
}
