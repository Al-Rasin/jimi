package com.alrasin.jarvis.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.core.isInstalled
import com.alrasin.jarvis.core.launch

/** "search X in Play Store" opens Play Store results by deep link (§M.8, §H.2), not a web search. */
fun storeSearchTool(context: Context): Tool =
    SimpleTool("store_search", "Search apps in the Google Play Store and show the results", listOf(arg("query", "app or game to look for"))) { c, _ ->
        val query = c.arg("query") ?: return@SimpleTool ToolResult.fail("Search the Play Store for what?")
        val opened = context.isInstalled(PLAY_STORE) &&
            context.launch(Intent(Intent.ACTION_VIEW, Uri.parse(storeSearchUri(query))).setPackage(PLAY_STORE))
        if (opened || context.launch(Intent(Intent.ACTION_VIEW, Uri.parse(storeWebUri(query))))) {
            ToolResult.ok("Here are the Play Store results for $query.")
        } else {
            ToolResult.fail("I couldn't open the Play Store.")
        }
    }

private const val PLAY_STORE = "com.android.vending"

internal fun storeSearchUri(query: String) = "market://search?q=${Uri.encode(query)}&c=apps"
internal fun storeWebUri(query: String) = "https://play.google.com/store/search?q=${Uri.encode(query)}&c=apps"
