package com.alrasin.jimi.brain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.random.Random

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
    private val PERCENT_OF = Regex("^(?:a )?(\\d+(?:\\.\\d+)?) ?(?:percent|%) (?:tip )?(?:of|on) (\\d+(?:\\.\\d+)?)$")
    private val ROOT = Regex("^(?:the )?square root of (\\d+(?:\\.\\d+)?)$")
    private val SQUARED = Regex("^(\\d+(?:\\.\\d+)?) (squared|cubed)$")
    private val POWER = Regex("^(\\d+(?:\\.\\d+)?) (?:to the power(?: of)?|raised to(?: the power(?: of)?)?|power|to the) (\\d{1,3})(?:st|nd|rd|th)?(?: power)?$")
    private val FRACTION = Regex("^(?:a |1 |one )?(half|third|quarter|fourth|fifth|tenth) of (\\d+(?:\\.\\d+)?)$")
    private val TIMES_WORD = Regex("^(double|triple|half) (?:of )?(\\d+(?:\\.\\d+)?)$")
    private val PERCENT_OFF = Regex("^(\\d+(?:\\.\\d+)?) ?(?:percent|%) (?:off|discount on|discount off) (?:of )?(\\d+(?:\\.\\d+)?)$")
    private val ROUND = Regex("^round(?: off)? (\\d+\\.\\d+)$")
    private val FACTORIAL = Regex("^(\\d{1,2}) factorial$|^(?:the )?factorial (?:of )?(\\d{1,2})$")
    private val PRIME = Regex("^is (\\d{1,12}) (?:a )?prime(?: number)?$")
    private val AVERAGE = Regex("^(?:the )?(?:average|mean) of (\\d+(?:\\.\\d+)?(?:(?: and)? \\d+(?:\\.\\d+)?)+)$")
    private val DIVISOR = mapOf("half" to 2, "third" to 3, "quarter" to 4, "fourth" to 4, "fifth" to 5, "tenth" to 10)
    private val PICK = Regex("^(?:(?:can you |please )?(?:pick|choose|give me|say|tell me) )?(?:a |any )?(?:random )?number (?:between|from) (\\d{1,9}) (?:and|to) (\\d{1,9})$")
    private val TOKEN = Regex("\\d+(?:\\.\\d+)?|[-+*/()]")
    private val EXPR = Regex("^[\\d.+\\-*/() ]+$")

    /** The spoken answer, or null when [input] is not a plain sum. */
    fun answer(input: String): String? {
        val t = prepare(input) ?: return null
        PICK.matchEntire(t)?.let { m ->
            val a = m.groupValues[1].toLong()
            val b = m.groupValues[2].toLong()
            return if (a <= b) "${Random.nextLong(a, b + 1)}." else null
        }
        PERCENT_OF.matchEntire(t)?.let { m ->
            val v = m.groupValues[1].toBigDecimal() * m.groupValues[2].toBigDecimal() / BigDecimal(100)
            return "${m.groupValues[1]} percent of ${m.groupValues[2]} is ${show(v)}."
        }
        special(t)?.let { return it }
        ROOT.matchEntire(t)?.let { m ->
            val v = Math.sqrt(m.groupValues[1].toDouble())
            return "The square root of ${m.groupValues[1]} is ${show(BigDecimal(v))}."
        }
        SQUARED.matchEntire(t)?.let { m ->
            val n = m.groupValues[1].toBigDecimal()
            val v = if (m.groupValues[2] == "squared") n * n else n * n * n
            return "${m.groupValues[1]} ${m.groupValues[2]} is ${show(v)}."
        }
        POWER.matchEntire(t)?.let { m ->
            val v = m.groupValues[1].toBigDecimal().pow(m.groupValues[2].toInt())
            if (v.precision() - v.scale() > 15) return null
            return "${m.groupValues[1]} to the power of ${m.groupValues[2]} is ${show(v)}."
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

    /** Fractions, double/half, discounts, rounding, factorial, primes, averages. */
    private fun special(t: String): String? {
        FRACTION.matchEntire(t)?.let { m ->
            val v = m.groupValues[2].toBigDecimal().divide(BigDecimal(DIVISOR.getValue(m.groupValues[1])), MathContext.DECIMAL64)
            val article = if (m.groupValues[1] == "half") "Half" else "A ${m.groupValues[1]}"
            return "$article of ${m.groupValues[2]} is ${show(v)}."
        }
        TIMES_WORD.matchEntire(t)?.let { m ->
            val n = m.groupValues[2].toBigDecimal()
            val v = when (m.groupValues[1]) { "double" -> n * BigDecimal(2); "triple" -> n * BigDecimal(3); else -> n.divide(BigDecimal(2)) }
            return "${m.groupValues[1].replaceFirstChar(Char::uppercase)} ${m.groupValues[2]} is ${show(v)}."
        }
        PERCENT_OFF.matchEntire(t)?.let { m ->
            val price = m.groupValues[2].toBigDecimal()
            val v = price - price * m.groupValues[1].toBigDecimal() / BigDecimal(100)
            return "${m.groupValues[2]} with ${m.groupValues[1]} percent off is ${show(v)}."
        }
        ROUND.matchEntire(t)?.let { m ->
            return "${m.groupValues[1]} rounded is ${m.groupValues[1].toBigDecimal().setScale(0, RoundingMode.HALF_UP).toPlainString()}."
        }
        FACTORIAL.matchEntire(t)?.let { m ->
            val n = m.groupValues[1].ifEmpty { m.groupValues[2] }.toInt()
            if (n > 20) return null
            val v = (1..n).fold(1L) { acc, k -> acc * k }
            return "$n factorial is $v."
        }
        PRIME.matchEntire(t)?.let { m ->
            val n = m.groupValues[1].toLong()
            if (n < 2) return "No, $n is not a prime number."
            var d = 2L
            while (d * d <= n) {
                if (n % d == 0L) return "No, $n is not prime. It divides by $d."
                d++
            }
            return "Yes, $n is a prime number."
        }
        AVERAGE.matchEntire(t)?.let { m ->
            val nums = m.groupValues[1].split(" ").filter { it != "and" }.map { it.toBigDecimal() }
            val v = nums.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(nums.size), MathContext.DECIMAL64)
            return "The average is ${show(v)}."
        }
        return null
    }

    private fun prepare(input: String): String? {
        var t = input.lowercase().trim().trimEnd('?', '.', '!', '=').trim()
            .replace("×", "*").replace("÷", "/").replace(",", "").replace("'", "").replace("’", "")
        t = t.replace(Regex("[^a-z0-9.+\\-*/()% ]"), " ").replace(Regex("\\s+"), " ").trim()
        t = NumberWords.normalize(t)
        t = LEAD.replace(t, "").let { TAIL.replace(it, "") }.trim()
        if (t.isEmpty() || t.none(Char::isDigit)) return null
        val whole = listOf(PERCENT_OF, ROOT, SQUARED, POWER, PICK, FRACTION, TIMES_WORD, PERCENT_OFF, ROUND, FACTORIAL, PRIME, AVERAGE)
        if (whole.any { it.matches(t) }) return t
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
