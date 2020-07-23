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

package net.octyl.beatdropper.util;

import static net.octyl.beatdropper.util.FFmpegMacros.av_err2str;
import static org.bytedeco.ffmpeg.global.swresample.swr_convert_frame;


import com.google.common.collect.AbstractIterator;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swresample.SwrContext;

public class SwrResampleIterator extends AbstractIterator<AVFrame> {

    private final SwrContext swrCtx;
    private final AVFrame output;
    private AVFrame currentFrame;

    public SwrResampleIterator(SwrContext swrCtx, AVFrame input, AVFrame output) {
        this.swrCtx = swrCtx;
        this.currentFrame = input;
        this.output = output;
    }

    @Override
    protected AVFrame computeNext() {
        int error = swr_convert_frame(swrCtx, output, currentFrame);
        if (error != 0) {
            throw new IllegalStateException("Error converting frame: " + av_err2str(error));
        }
        currentFrame = null;
        if (output.nb_samples() == 0) {
            // end of conversion currently
            return endOfData();
        }
        return output;
    }
}
