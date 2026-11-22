package io.github.patschgoo.castapp.settings

import android.content.Context
import androidx.core.content.edit
import io.github.patschgoo.castapp.subtitles.SubtitleBackground
import io.github.patschgoo.castapp.subtitles.SubtitleColor
import io.github.patschgoo.castapp.subtitles.SubtitleSize
import io.github.patschgoo.castapp.subtitles.SubtitleStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How subtitles look on the TV (Settings → Subtitles); kept apart from the casting settings. */
class SubtitleStyleSettings private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("subtitle_style", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SubtitleStyle> = _state.asStateFlow()
    val current: SubtitleStyle get() = _state.value

    private fun load(): SubtitleStyle {
        val d = SubtitleStyle()
        return SubtitleStyle(
            size = SubtitleSize.entries.firstOrNull { it.name == prefs.getString("size", null) } ?: d.size,
            color = SubtitleColor.entries.firstOrNull { it.name == prefs.getString("color", null) } ?: d.color,
            background = SubtitleBackground.entries.firstOrNull { it.name == prefs.getString("background", null) } ?: d.background,
        )
    }

    fun update(transform: (SubtitleStyle) -> SubtitleStyle) {
        val s = transform(_state.value)
        prefs.edit {
            putString("size", s.size.name)
            putString("color", s.color.name)
            putString("background", s.background.name)
        }
        _state.value = s
    }

    companion object {
        @Volatile private var instance: SubtitleStyleSettings? = null

        fun get(context: Context): SubtitleStyleSettings = instance ?: synchronized(this) {
            instance ?: SubtitleStyleSettings(context).also { instance = it }
        }
    }
}
