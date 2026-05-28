package com.lmstudio.client.data.api.dto

import com.google.gson.annotations.SerializedName

data class ModelListResponse(
    val data: List<ModelData>
)

data class NativeModelListResponse(
    val models: List<NativeModelData>
)

data class NativeModelData(
    val key: String,
    val type: String? = null,
    val publisher: String? = null,
    @SerializedName("display_name") val displayName: String? = null,
    val architecture: String? = null,
    val quantization: NativeQuantization? = null,
    @SerializedName("max_context_length") val maxContextLength: Long = 0L,
    val description: String? = null,
    val capabilities: NativeModelCapabilities? = null
) {
    fun toModelData(): ModelData = ModelData(
        id = key,
        type = type,
        publisher = publisher,
        arch = architecture,
        quantization = quantization?.name,
        maxContextLength = maxContextLength,
        description = description ?: displayName,
        capabilities = capabilities?.toModelCapabilities()
    )
}

data class NativeQuantization(
    val name: String? = null,
    @SerializedName("bits_per_weight") val bitsPerWeight: Double? = null
)

data class NativeModelCapabilities(
    val vision: Boolean = false,
    @SerializedName("trained_for_tool_use") val trainedForToolUse: Boolean = false
) {
    fun toModelCapabilities(): ModelCapabilities = ModelCapabilities(
        vision = vision,
        toolUse = trainedForToolUse
    )
}

data class ModelData(
    val id: String,
    @SerializedName("object") val objectType: String? = null,
    val type: String? = null,
    val publisher: String? = null,
    val arch: String? = null,
    val quantization: String? = null,
    @SerializedName("max_context_length") val maxContextLength: Long = 0L,
    val description: String? = null,
    val capabilities: ModelCapabilities? = null
)

data class ModelCapabilities(
    val vision: Boolean = false,
    @SerializedName("tool_use") val toolUse: Boolean = false
)

fun Long.briefContextLength(): String = when {
    this <= 0L -> ""
    this >= 1_048_576L -> "${this / 1_048_576}M"
    else -> "${this / 1_024}K"
}
