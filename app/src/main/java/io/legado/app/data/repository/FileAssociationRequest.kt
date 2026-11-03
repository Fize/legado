package io.legado.app.data.repository

import kotlinx.serialization.Serializable

@Serializable
enum class FileAssociationAction { View, Send, SendMultiple, Unsupported }

/** External input is cached separately so navigation stores only a request key. */
@Serializable
data class FileAssociationRequest(
    val action: FileAssociationAction,
    val mimeType: String? = null,
    val sourceUri: String? = null,
    val streams: List<String> = emptyList(),
    val text: String? = null,
)
