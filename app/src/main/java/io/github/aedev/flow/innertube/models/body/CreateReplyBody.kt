package io.github.aedev.flow.innertube.models.body

import io.github.aedev.flow.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class CreateReplyBody(
    val context: Context,
    val commentText: String,
    val createReplyParams: String? = null,
)
