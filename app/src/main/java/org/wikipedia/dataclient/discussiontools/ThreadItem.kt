package org.wikipedia.dataclient.discussiontools

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
class ThreadItem(
        val type: String = "",
        val level: Int = 0,
        val id: String = "",
        val name: String = "",
        val html: String = "",
        val author: String = "",
        val timestamp: String = "",
        val headingLevel: Int = 0,
        val placeholderHeading: Boolean = false,
        val replies: List<ThreadItem> = emptyList()
) {
    @Transient var isExpanded = false
}
