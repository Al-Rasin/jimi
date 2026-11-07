package com.alrasin.jarvis.actions

import android.content.Context
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult

/** "navigate to X" / "show X on the map" (§H.2): google.navigation: / geo: links, checked before opening. */
fun navigationTool(context: Context): Tool =
    SimpleTool(
        "open_map",
        "Show a place on the map, or start turn-by-turn directions to it",
        listOf(
            arg("place", "place, address or business to find"),
            arg("mode", "navigate = directions, show = just show it on the map", required = false, choices = listOf("navigate", "show")),
            arg("travel", "how to travel when navigating", required = false, choices = listOf("drive", "walk", "bike", "motorbike")),
        ),
    ) { c, _ ->
        val place = c.arg("place") ?: return@SimpleTool ToolResult.fail("Which place?")
        val navigate = c.arg("mode") == "navigate"
        val links = if (navigate) AppLinks.navigate(place, AppLinks.travel(c.arg("travel"))) else AppLinks.mapSearch(place)
        val used = context.openFirst(links) ?: return@SimpleTool ToolResult.fail("I couldn't open a map app.")
        when {
            navigate && used.uri?.startsWith("google.navigation:") == true -> ToolResult.ok("Starting directions to $place.")
            navigate -> ToolResult.ok("I couldn't start directions, so here is $place on the map.")
            else -> ToolResult.ok("Here is $place on the map.")
        }
    }
