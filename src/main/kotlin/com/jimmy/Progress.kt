package com.jimmy

import java.io.OutputStream
import java.io.PrintStream

object ProgressPrinter {
    private var doPrint = true
    private val map = mutableMapOf<String, Progress>()

    private var barsPrinted = 0
    private val originalOut = System.out

    init {
        System.setOut(PrintStream(object : OutputStream() {
            override fun write(b: Int) {
                synchronized(this@ProgressPrinter) {
                    clearBars()
                    originalOut.write(b)
                    redrawBars()
                }
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                synchronized(this@ProgressPrinter) {
                    clearBars()
                    originalOut.write(b, off, len)
                    redrawBars()
                }
            }
        }))
    }

    operator fun set(s: String, data: Progress) {
        synchronized(this) {
            map[s] = data
        }
    }

    operator fun get(s: String): Progress? = synchronized(this) { map[s] }

    fun doPrint(doPrint: Boolean) {
        synchronized(this) {
            this.doPrint = doPrint
            if (!doPrint) clearBars()
        }
    }

    private fun clearBars() {
        if (barsPrinted > 0) {
            originalOut.print("\u001B[${barsPrinted}A\u001B[J")
            originalOut.flush()
            barsPrinted = 0
        }
    }

    private fun redrawBars() {
        if (!doPrint || map.isEmpty()) return

        map.forEach { (key, p) ->
            val progress = if (p.total > 0) p.i.toDouble() / p.total else 0.0
            val barLength = 40
            val numChars = (progress * barLength).toInt().coerceIn(0, barLength)

            val completedBar = "█".repeat(numChars)
            val remainingBar = "░".repeat(barLength - numChars)
            val percentage = progress * 100

            originalOut.println("[\u001b[92m${completedBar}\u001b[0m$remainingBar] ${"\u001b[93m%05.1f%%\u001b[0m".format(percentage)} - \u001b[94m${key.padStart(15, ' ')}\u001b[0m : \u001b[91m${p.i}/${p.total}\u001b[0m")
        }
        originalOut.flush()
        barsPrinted = map.size
    }

    fun print() {
        synchronized(this) {
            clearBars()
            redrawBars()
        }
    }

    data class Progress(val total: Int) {
        var i: Int = 0

        fun update() {
            this.i = minOf(i + 1, total)
        }

        fun reset() {
            this.i = 0
        }
    }
}
