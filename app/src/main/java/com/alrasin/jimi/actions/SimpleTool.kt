package com.alrasin.jimi.actions

import com.alrasin.jimi.core.ArgSpec
import com.alrasin.jimi.core.MissingArg
import com.alrasin.jimi.core.Risk
import com.alrasin.jimi.core.Tool
import com.alrasin.jimi.core.ToolCall
import com.alrasin.jimi.core.ToolContext
import com.alrasin.jimi.core.ToolResult

/** Small helper so each tool is one block: name, description, args, risk, code. */
class SimpleTool(
    override val name: String,
    override val description: String,
    override val args: List<ArgSpec> = emptyList(),
    override val risk: Risk = Risk.SAFE,
    private val block: suspend (ToolCall, ToolContext) -> ToolResult,
) : Tool {
    override suspend fun run(call: ToolCall, ctx: ToolContext): ToolResult =
        try {
            block(call, ctx)
        } catch (e: MissingArg) {
            ToolResult.fail(e.ask)
        }
}

fun arg(name: String, description: String, required: Boolean = true, choices: List<String>? = null) =
    ArgSpec(name, description, required, choices)
