package com.jimmy

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object IntSetSerializer : KSerializer<Set<Int>> {
    private val delegate = SetSerializer(Int.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Set<Int>) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): Set<Int> {
        return try {
            delegate.deserialize(decoder)
        } catch (_: Exception) {
            try {
                val str = decoder.decodeString()
                parseIntSelection(str)
            } catch (_: Exception) {
                setOf(decoder.decodeInt())
            }
        }
    }
}

@Serializable
data class Config(
    val test: Boolean = false,
    val plot: Boolean = false,
    @Serializable(with = IntSetSerializer::class)
    val version: Set<Int>? = null,
    @Serializable(with = IntSetSerializer::class)
    val difficulty: Set<Int>? = null,
    val iterations: Int = 100,
    val grid: String? = null,
    val printProgress: Boolean = false,
    val printTime: Boolean = false,
    val printNice: Boolean = false,
    val printDebug: Boolean = false,
    val path: String = "benchmarks",
    val plotPath: String = "plots",
)