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

import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable
import org.bytedeco.ffmpeg.global.swresample.swr_convert_frame
import org.bytedeco.ffmpeg.global.swresample.swr_next_pts
import org.bytedeco.ffmpeg.swresample.SwrContext

fun swrResampleSequence(
    swrCtx: SwrContext,
    input: AVFrame,
    output: AVFrame
): Sequence<AVFrame> = sequence {
    val pts = swr_next_pts(swrCtx, input.pts())
    var currentFrame: AVFrame? = input
    while (true) {
        av_frame_make_writable(output)
        val error = swr_convert_frame(swrCtx, output, currentFrame)
        check(error == 0) { "Error converting frame: " + avErr2Str(error) }
        currentFrame = null
        if (output.nb_samples() == 0) {
            break
        }
        output.pts(pts)
        yield(output)
    }
}
