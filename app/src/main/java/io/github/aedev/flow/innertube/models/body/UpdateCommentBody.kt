package io.github.aedev.flow.innertube.models.body

import io.github.aedev.flow.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class UpdateCommentBody(
    val context: Context,
    val commentText: String,
    val updateCommentParams: String,
)

@Serializable
data class CommentActionBody(
    val context: Context,
    val actions: List<String>,
)
