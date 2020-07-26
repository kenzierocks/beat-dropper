package net.octyl.beatdropper.util

import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avutil

data class Format(
    val channelLayout: Long,
    val sampleFormat: Int,
    val timeBase: AVRational,
    val sampleRate: Int
)

fun AVFrame.setFrom(format: Format): AVFrame = apply {
    channel_layout(format.channelLayout)
    format(format.sampleFormat)
    sample_rate(format.sampleRate)
}

fun internalFormat(sampleRate: Int) = Format(
    avutil.AV_CH_LAYOUT_STEREO,
    avutil.AV_SAMPLE_FMT_S16,
    avutil.av_make_q(1, sampleRate),
    sampleRate
)
