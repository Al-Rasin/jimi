package com.alrasin.jarvis.brain

/** One past user/assistant exchange, used as chat history. */
data class Exchange(val user: String, val reply: String)
