package com.alrasin.jimi.brain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * "what is 25 times 4", "15 percent of 200", "square root of 81", "12 / 5": answered at once, offline.
 * The small chat model is slow and often wrong at arithmetic, so plain sums never reach it.
 */
object QuickMath {
    private val LEAD = Regex("^(?:(?:hey |ok |okay )?(?:jimi |jimmy )?)?(?:(?:can you |please )?(?:calculate|compute|work out|solve)|what(?:s| is| are)|how much is|how much are|tell me)? ?")
    private val TAIL = Regex(" ?(?:equals?|equal to|is equal to|make|makes)?\\s*$")
    private val WORD_OPS = listOf(
        "multiplied by" to "*", "times" to "*", "into" to "*", "x" to "*",
        "divided by" to "/", "over" to "/", "plus" to "+",
        "minus" to "-", "less" to "-", "take away" to "-",
    )
    private val PERCENT_OF = Regex("^(\\d+(?:\\.\\d+)?) ?(?:percent|%) of (\\d+(?:\\.\\d+)?)$")
    private val ROOT = Regex("^(?:the )?square root of (\\d+(?:\\.\\d+)?)$")
    private val SQUARED = Regex("^(\\d+(?:\\.\\d+)?) (squared|cubed)$")
    private val TOKEN = Regex("\\d+(?:\\.\\d+)?|[-+*/()]")
    private val EXPR = Regex("^[\\d.+\\-*/() ]+$")

    /** The spoken answer, or null when [input] is not a plain sum. */
    fun answer(input: String): String? {
        val t = prepare(input) ?: return null
        PERCENT_OF.matchEntire(t)?.let { m ->
            val v = m.groupValues[1].toBigDecimal() * m.groupValues[2].toBigDecimal() / BigDecimal(100)
            return "${m.groupValues[1]} percent of ${m.groupValues[2]} is ${show(v)}."
        }
        ROOT.matchEntire(t)?.let { m ->
            val v = Math.sqrt(m.groupValues[1].toDouble())
            return "The square root of ${m.groupValues[1]} is ${show(BigDecimal(v))}."
        }
        SQUARED.matchEntire(t)?.let { m ->
            val n = m.groupValues[1].toBigDecimal()
            val v = if (m.groupValues[2] == "squared") n * n else n * n * n
            return "${m.groupValues[1]} ${m.groupValues[2]} is ${show(v)}."
        }
        if (!EXPR.matches(t) || t.none { it in "+-*/" } || t.none(Char::isDigit)) return null
        val tokens = TOKEN.findAll(t).map { it.value }.toList()
        // A lone negative number ("-5") is not a sum.
        if (tokens.size < 3) return null
        val value = try {
            Parser(tokens).parse()
        } catch (_: DivideByZero) {
            return "You can't divide by zero, ${Persona.ADDRESS}."
        } catch (_: RuntimeException) {
            return null
        }
        return "${spoken(tokens)} is ${show(value)}."
    }

    private fun prepare(input: String): String? {
        var t = input.lowercase().trim().trimEnd('?', '.', '!', '=').trim()
            .replace("×", "*").replace("÷", "/").replace(",", "").replace("'", "").replace("’", "")
        t = t.replace(Regex("[^a-z0-9.+\\-*/()% ]"), " ").replace(Regex("\\s+"), " ").trim()
        t = NumberWords.normalize(t)
        t = LEAD.replace(t, "").let { TAIL.replace(it, "") }.trim()
        if (t.isEmpty() || t.none(Char::isDigit)) return null
        if (PERCENT_OF.matches(t) || ROOT.matches(t) || SQUARED.matches(t)) return t
        for ((w, op) in WORD_OPS) t = t.replace(Regex("(?<=\\d|\\)) $w (?=\\d|\\()"), " $op ")
        // "5x3" typed without spaces
        t = t.replace(Regex("(?<=\\d)x(?=\\d)"), "*")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private fun spoken(tokens: List<String>) = tokens.joinToString(" ") {
        when (it) { "*" -> "times"; "/" -> "divided by"; "+" -> "plus"; "-" -> "minus"; else -> it }
    }.replace("( ", "(").replace(" )", ")")

    private fun show(v: BigDecimal): String {
        val r = v.round(MathContext(12)).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()
        return if (r.signum() == 0) "0" else r.toPlainString()
    }

    private class DivideByZero : RuntimeException()

    /** Plain precedence climbing: expr = term (+|- term)*, term = factor (*|/ factor)*. */
    private class Parser(private val tokens: List<String>) {
        private var i = 0
        fun parse(): BigDecimal {
            val v = expr()
            check(i == tokens.size)
            return v
        }
        private fun expr(): BigDecimal {
            var v = term()
            while (i < tokens.size && tokens[i] in setOf("+", "-")) {
                val op = tokens[i++]
                val r = term()
                v = if (op == "+") v + r else v - r
            }
            return v
        }
        private fun term(): BigDecimal {
            var v = factor()
            while (i < tokens.size && tokens[i] in setOf("*", "/")) {
                val op = tokens[i++]
                val r = factor()
                if (op == "/" && r.signum() == 0) throw DivideByZero()
                v = if (op == "*") v * r else v.divide(r, MathContext.DECIMAL64)
            }
            return v
        }
        private fun factor(): BigDecimal {
            val tok = tokens[i++]
            return when (tok) {
                "(" -> expr().also { check(tokens[i++] == ")") }
                "-" -> factor().negate()
                else -> tok.toBigDecimal()
            }
        }
    }
}
