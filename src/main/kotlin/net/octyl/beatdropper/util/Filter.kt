package net.octyl.beatdropper.util

import org.bytedeco.ffmpeg.avfilter.AVFilterContext

data class Filter(
    val id: String,
    val name: String,
    val configure: (ctx: AVFilterContext) -> Unit
)
