package com.alrasin.jarvis.actions

import com.alrasin.jarvis.core.ArgSpec
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolContext
import com.alrasin.jarvis.core.ToolResult

/** Small helper so each tool is one block: name, description, args, risk, code. */
class SimpleTool(
    override val name: String,
    override val description: String,
    override val args: List<ArgSpec> = emptyList(),
    override val risk: Risk = Risk.SAFE,
    private val block: suspend (ToolCall, ToolContext) -> ToolResult,
) : Tool {
    override suspend fun run(call: ToolCall, ctx: ToolContext): ToolResult = block(call, ctx)
}

fun arg(name: String, description: String, required: Boolean = true, choices: List<String>? = null) =
    ArgSpec(name, description, required, choices)
