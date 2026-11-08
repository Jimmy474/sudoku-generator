package com.jimmy

data class Config(
    val test: Boolean = false,
    val plot: Boolean = false,
    val version: Set<Int>? = null,
    val difficulty: Set<Int>? = null,
    val iterations: Int = 100,
    val grid: String? = null,
    val printProgress: Boolean = false,
    val printTime: Boolean = false,
    val printNice: Boolean = false,
    val printDebug: Boolean = false
)