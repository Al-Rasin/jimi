package com.alrasin.jarvis.llm

import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolCall

/**
 * FunctionGemma prompt + output format (PLAN 9.2), and a GBNF grammar built from the tool list so the
 * model can only write a valid call to a real tool (or plain text = "no tool").
 * Pure Kotlin: unit tested.
 */
object FunctionGemma {
    private const val ESC = "<escape>"

    fun declarations(tools: List<Tool>): String = tools.joinToString("") { t ->
        val props = t.args.joinToString(",") { a ->
            val enum = a.choices?.let { c -> ",enum:[${c.joinToString(",") { "$ESC$it$ESC" }}]" } ?: ""
            "${a.name}:{description:$ESC${a.description}$ESC$enum,type:${ESC}STRING$ESC}"
        }
        val req = t.args.filter { it.required }.joinToString(",") { "$ESC${it.name}$ESC" }
        "<start_function_declaration>declaration:${t.name}{description:$ESC${t.description}$ESC," +
            "parameters:{properties:{$props},required:[$req],type:${ESC}OBJECT$ESC}}<end_function_declaration>"
    }

    /** System part is identical every call → stays in the KV cache. */
    fun prompt(tools: List<Tool>, context: String, user: String): String =
        "<bos><start_of_turn>developer\nYou are a model that can do function calling with the following functions" +
            declarations(tools) + "<end_of_turn>\n" +
            "<start_of_turn>user\n$user\n($context)<end_of_turn>\n<start_of_turn>model\n"

    /** call:name{key:<escape>value<escape>,...} → ToolCall. Null if no call. */
    fun parse(out: String): ToolCall? {
        val m = Regex("<start_function_call>call:([a-z_]+)\\{(.*?)\\}(?:<end_function_call>|$)", RegexOption.DOT_MATCHES_ALL).find(out)
            ?: return null
        val args = Regex("([a-z_]+):(?:$ESC(.*?)$ESC|([^,}]*))", RegexOption.DOT_MATCHES_ALL).findAll(m.groupValues[2])
            .associate { it.groupValues[1] to (it.groupValues[2].ifEmpty { it.groupValues[3] }).trim() }
            .filterValues { it.isNotEmpty() }
        return ToolCall(m.groupValues[1], args)
    }

    /** GBNF: either one function call to a known tool with its own arg names, or short plain text. */
    fun grammar(tools: List<Tool>): String {
        val sb = StringBuilder()
        sb.append("root ::= [ \\n]* (call | text)\n")  // model often starts with a newline
        sb.append("text ::= [^<] [^<]*\n")
        sb.append("call ::= \"<start_function_call>call:\" (")
        sb.append(tools.indices.joinToString(" | ") { "t$it" })
        sb.append(") \"}<end_function_call>\"\n")
        sb.append("val ::= \"$ESC\" [^<]+ \"$ESC\"\n")
        tools.forEachIndexed { i, t ->
            val args = t.args.joinToString(" ") { a ->
                val v = a.choices?.joinToString(" | ") { "\"$ESC$it$ESC\"" }?.let { "($it)" } ?: "val"
                "(\"${a.name}:\" $v \",\"?)?"
            }
            sb.append("t$i ::= \"${t.name}{\" $args\n")
        }
        return sb.toString()
    }
}
