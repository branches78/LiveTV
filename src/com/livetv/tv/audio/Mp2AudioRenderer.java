/*
 * Copyright (C) 2026 The LiveTV Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.livetv.tv.audio;

import android.os.Handler;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;

/**
 * Plays MPEG audio layer I/II (MP1/MP2) with {@link Mp2Decoder}. Most TS channels carry MP2
 * audio, and some TVs (the Google TV Streamer, for one) have no decoder for it: ExoPlayer then
 * played those channels without sound.
 *
 * <p>{@link LiveRenderersFactory} adds it after the platform's renderer, so a TV with its own MP2
 * decoder keeps using that one.
 */
@UnstableApi
public final class Mp2AudioRenderer extends DecoderAudioRenderer<Mp2Decoder> {
    private static final String TAG = "Mp2AudioRenderer";

    public Mp2AudioRenderer(
            @Nullable Handler eventHandler,
            @Nullable AudioRendererEventListener eventListener,
            AudioSink audioSink) {
        super(eventHandler, eventListener, audioSink);
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    protected int supportsFormatInternal(Format format) {
        if (!MimeTypes.AUDIO_MPEG_L2.equals(format.sampleMimeType)
                && !MimeTypes.AUDIO_MPEG_L1.equals(format.sampleMimeType)) {
            return C.FORMAT_UNSUPPORTED_TYPE;
        }
        if (format.channelCount < 1
                || format.channelCount > 2
                || !sinkSupportsFormat(pcmFormat(format.channelCount, format.sampleRate))) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE;
        }
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
            return C.FORMAT_UNSUPPORTED_DRM;
        }
        return C.FORMAT_HANDLED;
    }

    @Override
    protected Mp2Decoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig) {
        return new Mp2Decoder(format.channelCount, format.sampleRate);
    }

    @Override
    protected Format getOutputFormat(Mp2Decoder decoder) {
        return pcmFormat(decoder.getChannelCount(), decoder.getSampleRate());
    }

    private static Format pcmFormat(int channelCount, int sampleRate) {
        return Util.getPcmFormat(C.ENCODING_PCM_16BIT, channelCount, sampleRate);
    }
}
