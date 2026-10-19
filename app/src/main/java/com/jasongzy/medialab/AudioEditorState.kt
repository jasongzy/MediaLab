package com.jasongzy.medialab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kyant.taglib.Picture

internal class AudioEditorState {
    var tag by mutableStateOf<TagDraft?>(null)
    var picture by mutableStateOf<PictureDraft?>(null)
    var coverRequest by mutableStateOf<CoverRequest?>(null)

    val hasDraft: Boolean
        get() = tag != null || picture != null
}

internal class TagDraft(val original: String, values: List<String>) {
    var key by mutableStateOf(original)
    var text by mutableStateOf(values.joinToString("\n"))
}

internal class PictureDraft(val index: Int, picture: Picture) {
    var description by mutableStateOf(picture.description)
    var type by mutableStateOf(picture.pictureType)
    var deleting by mutableStateOf(false)
}

internal data class CoverRequest(val index: Int? = null)
