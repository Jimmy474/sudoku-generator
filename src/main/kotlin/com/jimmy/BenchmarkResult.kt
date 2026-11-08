package com.jimmy

import org.jetbrains.kotlinx.dataframe.annotations.DataSchema

@DataSchema
data class BenchmarkResult(
    val version: Int,
    val grid: String,
    val difficulty: Int,
    val actualDifficulty: Int,
    val runtimeMs: Float,
    val peakMemUsage: Long,
)
