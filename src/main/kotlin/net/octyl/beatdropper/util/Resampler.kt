/*
 * This file is part of beat-dropper, licensed under the MIT License (MIT).
 *
 * Copyright (c) Octavia Togami <https://octyl.net>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package net.octyl.beatdropper.util

import org.bytedeco.ffmpeg.avfilter.AVFilter
import org.bytedeco.ffmpeg.avfilter.AVFilterContext
import org.bytedeco.ffmpeg.avfilter.AVFilterGraph
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avfilter.av_buffersink_get_frame
import org.bytedeco.ffmpeg.global.avfilter.av_buffersrc_add_frame
import org.bytedeco.ffmpeg.global.avfilter.avfilter_get_by_name
import org.bytedeco.ffmpeg.global.avfilter.avfilter_graph_alloc
import org.bytedeco.ffmpeg.global.avfilter.avfilter_graph_alloc_filter
import org.bytedeco.ffmpeg.global.avfilter.avfilter_graph_config
import org.bytedeco.ffmpeg.global.avfilter.avfilter_graph_free
import org.bytedeco.ffmpeg.global.avfilter.avfilter_init_dict
import org.bytedeco.ffmpeg.global.avfilter.avfilter_link
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EAGAIN
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AV_OPT_SEARCH_CHILDREN
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_clone
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.ffmpeg.global.avutil.av_opt_set
import org.bytedeco.ffmpeg.global.avutil.av_opt_set_int
import org.bytedeco.ffmpeg.global.avutil.av_opt_set_q

class Resampler(
    inputFormat: Format,
    outputFormat: Format
) : AutoCloseable {

    private val closer = AutoCloser()

    private val graph: AVFilterGraph
    private val bufferCtx: AVFilterContext
    private val buffer: AVFilter
    private val resampleCtx: AVFilterContext
    private val resample: AVFilter
    private val bufferSinkCtx: AVFilterContext
    private val bufferSink: AVFilter
    private val formatCtx: AVFilterContext
    private val format: AVFilter
    private val outputFrame: AVFrame

    init {
        graph = closer.register(
            avfilter_graph_alloc(),
            { avfilter_graph_free(it) }
        ) ?: error("Unable to allocate filter graph")

        buffer = avfilter_get_by_name("abuffer") ?: error("Unable to find abuffer")
        bufferCtx = avfilter_graph_alloc_filter(graph, buffer, "src") ?: error("Unable to allocate abuffer ctx")

        av_opt_set(bufferCtx, "channel_layout", channelLayoutName(inputFormat.channelLayout), AV_OPT_SEARCH_CHILDREN)
        av_opt_set_int(bufferCtx, "sample_fmt", inputFormat.sampleFormat.toLong(), AV_OPT_SEARCH_CHILDREN)
        av_opt_set_q(bufferCtx, "time_base", inputFormat.timeBase, AV_OPT_SEARCH_CHILDREN)
        av_opt_set_int(bufferCtx, "sample_rate", inputFormat.sampleRate.toLong(), AV_OPT_SEARCH_CHILDREN)

        checkAv(avfilter_init_dict(bufferCtx, null as AVDictionary?)) {
            "Unable to initialize abuffer filter: $it"
        }

        resample = avfilter_get_by_name("aresample") ?: error("Unable to find aresample")
        resampleCtx = avfilter_graph_alloc_filter(graph, resample, "resample")
            ?: error("Unable to allocate aresample ctx")

        checkAv(avfilter_init_dict(resampleCtx, null as AVDictionary?)) {
            "Unable to initialize aresample filter: $it"
        }


        format = avfilter_get_by_name("aformat") ?: error("Unable to find aformat")
        formatCtx = avfilter_graph_alloc_filter(graph, format, "format") ?: error("Unable to allocate aformat ctx")

        av_opt_set(formatCtx, "channel_layouts", channelLayoutName(outputFormat.channelLayout), AV_OPT_SEARCH_CHILDREN)
        avOptSetList(formatCtx, "sample_fmts", intArrayOf(outputFormat.sampleFormat), AV_OPT_SEARCH_CHILDREN)
        avOptSetList(formatCtx, "sample_rates", intArrayOf(outputFormat.sampleRate), AV_OPT_SEARCH_CHILDREN)

        checkAv(avfilter_init_dict(formatCtx, null as AVDictionary?)) {
            "Unable to initialize aformat filter: $it"
        }

        bufferSink = avfilter_get_by_name("abuffersink") ?: error("Unable to find abuffersink")
        bufferSinkCtx = avfilter_graph_alloc_filter(graph, bufferSink, "sink")
            ?: error("Unable to allocate abuffersink ctx")

        avOptSetList(bufferSinkCtx, "channel_layouts", longArrayOf(outputFormat.channelLayout), AV_OPT_SEARCH_CHILDREN)
        avOptSetList(bufferSinkCtx, "sample_fmts", intArrayOf(outputFormat.sampleFormat), AV_OPT_SEARCH_CHILDREN)
        avOptSetList(bufferSinkCtx, "sample_rates", intArrayOf(outputFormat.sampleRate), AV_OPT_SEARCH_CHILDREN)

        checkAv(avfilter_init_dict(bufferSinkCtx, null as AVDictionary?)) {
            "Unable to initialize abuffersink filter: $it"
        }

        checkAv(avfilter_link(bufferCtx, 0, resampleCtx, 0)) {
            "Unable to link buffer to resample"
        }
        checkAv(avfilter_link(resampleCtx, 0, formatCtx, 0)) {
            "Unable to link resample to format"
        }
        checkAv(avfilter_link(formatCtx, 0, bufferSinkCtx, 0)) {
            "Unable to link format to buffer sink"
        }

        checkAv(avfilter_graph_config(graph, null)) {
            "Unable to configure filter graph"
        }

        outputFrame = closer.register(av_frame_alloc()) { av_frame_free(it) }
            ?: error("Unable to allocate output frame")
    }

    /**
     * Push a single frame into the resampler, and get the [0, N] frames it produces.
     */
    fun pushFrame(frame: AVFrame): Iterator<AVFrame> {
        val ourFrame = av_frame_clone(frame) ?: error("Unable to clone frame")
        checkAv(av_buffersrc_add_frame(bufferCtx, ourFrame)) {
            av_frame_unref(frame)
            "Unable to push frame into graph"
        }
        av_frame_free(ourFrame)

        return sequence {
            var error: Int
            while (true) {
                error = av_buffersink_get_frame(bufferSinkCtx, outputFrame)
                when {
                    error == AVERROR_EAGAIN() || error == AVERROR_EOF -> return@sequence
                    error < 0 -> error("Error filtering frame: ${avErr2Str(error)}")
                }
                yield(outputFrame)
                av_frame_unref(outputFrame)
            }
        }.iterator()
    }

    override fun close() {
        closer.close()
    }

}

data class Format(
    val channelLayout: Long,
    val sampleFormat: Int,
    val timeBase: AVRational,
    val sampleRate: Int
)
