package com.alrasin.jarvis.voice

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

class PhraseCache(
    dir: File,
    val voiceId: String,
    val speed: Float,
    val sampleRate: Int,
    private val maxBytes: Long = 30L * 1024 * 1024,
) {
    data class Audio(val samples: FloatArray, val sampleRate: Int)

    private class State {
        var epoch = 0L
        var lastTouch = 0L
    }

    private val dir = dir.canonicalFile
    private val state = states.computeIfAbsent(this.dir.path) { State() }

    init {
        require(voiceId.isNotBlank())
        require(speed.isFinite() && speed > 0)
        require(sampleRate > 0)
        require(maxBytes >= 0)
        synchronized(state) { quietly { evict() } }
    }

    fun get(text: String): Audio? = synchronized(state) {
        val file = file(text)
        try {
            if (!file.isFile) return@synchronized null
            val size = file.length()
            if (size < OVERHEAD || size > maxBytes) throw IOException("Invalid cache size")
            val audio = DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException("Invalid cache header")
                val rate = input.readInt()
                val count = input.readInt()
                if (rate <= 0 || count <= 0 || OVERHEAD + count.toLong() * 4 != size) {
                    throw IOException("Invalid PCM length or rate")
                }
                val crc = CRC32()
                crc.addInt(rate)
                crc.addInt(count)
                val samples = FloatArray(count) {
                    val bits = input.readInt()
                    crc.addInt(bits)
                    Float.fromBits(bits).also { if (!it.isFinite()) throw IOException("Invalid PCM sample") }
                }
                if (input.readLong() != crc.value) throw IOException("Invalid PCM checksum")
                Audio(samples, rate)
            }
            touch(file)
            audio
        } catch (_: IOException) {
            quietly { file.delete() }
            null
        } catch (_: SecurityException) {
            quietly { file.delete() }
            null
        }
    }

    fun put(text: String, samples: FloatArray, sampleRate: Int = this.sampleRate) {
        require(sampleRate > 0)
        if (samples.isEmpty() || samples.any { !it.isFinite() } || OVERHEAD + samples.size.toLong() * 4 > maxBytes) return
        synchronized(state) {
            quietly {
                if (!dir.isDirectory && !dir.mkdirs()) return@quietly
                val target = file(text)
                val temporary = File.createTempFile("phrase-", ".tmp", dir)
                try {
                    DataOutputStream(temporary.outputStream().buffered()).use { output ->
                        output.writeInt(MAGIC)
                        output.writeInt(VERSION)
                        output.writeInt(sampleRate)
                        output.writeInt(samples.size)
                        val crc = CRC32()
                        crc.addInt(sampleRate)
                        crc.addInt(samples.size)
                        samples.forEach {
                            val bits = it.toRawBits()
                            output.writeInt(bits)
                            crc.addInt(bits)
                        }
                        output.writeLong(crc.value)
                    }
                    if (temporary.renameTo(target)) {
                        touch(target)
                        evict()
                    }
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    fun clear() = synchronized(state) {
        state.epoch++
        quietly { entries().forEach { it.delete() } }
    }

    fun warm(generate: (String) -> FloatArray?) {
        val epoch = synchronized(state) { state.epoch }
        for (text in COMMON) {
            synchronized(state) { if (epoch != state.epoch) return }
            if (get(text) != null) continue
            val samples = generate(text) ?: continue
            synchronized(state) {
                if (epoch != state.epoch) return
                if (get(text) == null) put(text, samples)
            }
        }
    }

    private fun file(text: String): File {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            val voice = voiceId.toByteArray(Charsets.UTF_8)
            output.writeInt(voice.size)
            output.write(voice)
            output.writeInt(speed.toRawBits())
            output.write(text.replace(WHITESPACE, " ").trim().toByteArray(Charsets.UTF_8))
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(dir, "$hash.pcmcache")
    }

    private fun entries() = dir.listFiles { file -> file.isFile && ENTRY.matches(file.name) }.orEmpty()

    private fun touch(file: File) {
        state.lastTouch = maxOf(state.lastTouch, entries().maxOfOrNull { it.lastModified() } ?: 0L)
        state.lastTouch = maxOf(System.currentTimeMillis(), state.lastTouch + 1)
        file.setLastModified(state.lastTouch)
    }

    private fun evict() {
        val files = entries().sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
    }

    private fun CRC32.addInt(value: Int) {
        update(value ushr 24)
        update(value ushr 16)
        update(value ushr 8)
        update(value)
    }

    companion object {
        private const val MAGIC = 0x4a50434d
        private const val VERSION = 1
        private const val OVERHEAD = 24L
        private val WHITESPACE = Regex("[\\s\\p{Z}]+")
        private val ENTRY = Regex("[0-9a-f]{64}\\.pcmcache")
        private val states = ConcurrentHashMap<String, State>()

        val COMMON: List<String> = listOf(
            "Uh-huh.",
            "Let me check.",
            "On it.",
            "Hmm, one sec.",
            "Boss, Flashlight is on.",
            "Boss, Flashlight is off.",
            "Done.",
            "Sorry, I didn't catch that.",
            "Sorry Boss, I can't do that yet.",
            "Hello Boss. What can I do?",
            "Any time, Boss.",
            "I'm here, Boss.",
            "All good, Boss. Ready when you are.",
            "Bye, Boss.",
            "Flashlight on.",
            "Flashlight off.",
            "Flashlight is already on.",
            "Flashlight is already off.",
            "Paused.",
            "Playing.",
            "Next.",
            "Previous.",
            "Volume 0 percent.",
            "Volume 25 percent.",
            "Volume 50 percent.",
            "Volume 75 percent.",
            "Volume 100 percent.",
            "Okay, I asked the clock to turn them off.",
            "Screenshot saved.",
            "Locked.",
            "Unlocked.",
            "Typed.",
            "Scrolled.",
        ) + listOf("Wi-Fi", "Bluetooth", "Mobile data", "Do not disturb", "Airplane mode",
            "Location", "Battery saver", "Auto rotate").flatMap { label ->
            listOf("Boss, $label is on.", "Boss, $label is off.")
        }
    }
}
