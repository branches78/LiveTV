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

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.decoder.SampleBuffer;

import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Decodes MPEG audio layer I and II (MP1/MP2) to 16-bit PCM with JLayer, in Java. Each input
 * buffer holds whole frames, as the TS extractor gives them.
 *
 * <p>The output always has the channels of the format the renderer was given: a frame with other
 * channels (rare, a broadcast switching between mono and stereo) is converted to them.
 */
@UnstableApi
final class Mp2Decoder
        extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {
    private static final int BUFFER_COUNT = 16;
    /** Larger than any layer I/II frame (about 1.7 KB at most). */
    private static final int INITIAL_INPUT_SIZE = 4096;
    /** Samples per channel in a layer II frame (layer I has 384). */
    private static final int MAX_SAMPLES_PER_FRAME = 1152;

    private final int mChannelCount;
    private final int mSampleRate;
    private byte[] mInput = new byte[INITIAL_INPUT_SIZE];
    private final FeedInputStream mFeed = new FeedInputStream();
    /**
     * Reads the frames of every buffer from {@link #mFeed}. It must last as long as {@link
     * #mDecoder}: JLayer's layer decoders keep the bitstream they were first given.
     */
    @Nullable private Bitstream mBitstream;
    @Nullable private Decoder mDecoder;
    /** Channels of the frames {@link #mDecoder} was created for. */
    private int mDecoderChannels;

    Mp2Decoder(int channelCount, int sampleRate) {
        super(
                new DecoderInputBuffer[BUFFER_COUNT],
                new SimpleDecoderOutputBuffer[BUFFER_COUNT]);
        mChannelCount = channelCount;
        mSampleRate = sampleRate;
        setInitialInputBufferSize(INITIAL_INPUT_SIZE);
    }

    int getChannelCount() {
        return mChannelCount;
    }

    int getSampleRate() {
        return mSampleRate;
    }

    @Override
    public String getName() {
        return "livetv-jlayer-mp2";
    }

    @Override
    protected DecoderInputBuffer createInputBuffer() {
        return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    }

    @Override
    protected SimpleDecoderOutputBuffer createOutputBuffer() {
        return new SimpleDecoderOutputBuffer(this::releaseOutputBuffer);
    }

    @Override
    protected DecoderException createUnexpectedDecodeException(Throwable error) {
        return new DecoderException("Unexpected MP2 decode error", error);
    }

    @Nullable
    @Override
    protected DecoderException decode(
            DecoderInputBuffer inputBuffer, SimpleDecoderOutputBuffer outputBuffer, boolean reset) {
        if (reset) {
            // After a seek, the synthesis filter must not mix in the audio from before.
            mBitstream = null;
            mDecoder = null;
        }
        ByteBuffer data = inputBuffer.data;
        int size = data.remaining();
        if (mInput.length < size) {
            mInput = new byte[size];
        }
        data.get(mInput, 0, size);
        mFeed.set(mInput, size);

        // A buffer normally holds one frame; room for a few, grown below if needed.
        ByteBuffer output =
                outputBuffer.init(inputBuffer.timeUs, 4 * MAX_SAMPLES_PER_FRAME * mChannelCount);
        try {
            if (mBitstream == null) {
                mBitstream = new Bitstream(mFeed);
            }
            Header header;
            while ((header = mBitstream.readFrame()) != null) {
                int frameChannels = header.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
                if (mDecoder == null || mDecoderChannels != frameChannels) {
                    mDecoder = new Decoder();
                    mDecoderChannels = frameChannels;
                }
                SampleBuffer samples = (SampleBuffer) mDecoder.decodeFrame(header, mBitstream);
                mBitstream.closeFrame();
                output = write(samples, frameChannels, outputBuffer, output);
            }
        } catch (JavaLayerException | RuntimeException e) {
            // A damaged frame (lost TS packets): keep what was decoded and start over with the
            // next buffer, instead of stopping the audio of the channel.
            mBitstream = null;
            mDecoder = null;
        }
        output.flip();
        if (!output.hasRemaining()) {
            outputBuffer.shouldBeSkipped = true;
        }
        return null;
    }

    /**
     * The bytes of the current input buffer; at their end it reports the end of the stream, and
     * goes on with the next buffer once given. The bitstream sees a single stream of frames.
     */
    private static final class FeedInputStream extends InputStream {
        private byte[] mBuffer = new byte[0];
        private int mPosition;
        private int mEnd;

        void set(byte[] buffer, int length) {
            mBuffer = buffer;
            mPosition = 0;
            mEnd = length;
        }

        @Override
        public int read() {
            return mPosition < mEnd ? mBuffer[mPosition++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            if (mPosition >= mEnd) {
                return -1;
            }
            int count = Math.min(length, mEnd - mPosition);
            System.arraycopy(mBuffer, mPosition, buffer, offset, count);
            mPosition += count;
            return count;
        }

        @Override
        public int available() {
            return mEnd - mPosition;
        }
    }

    /** Appends the frame as 16-bit PCM with {@link #mChannelCount} channels. */
    private ByteBuffer write(
            SampleBuffer samples,
            int frameChannels,
            SimpleDecoderOutputBuffer outputBuffer,
            ByteBuffer output) {
        short[] pcm = samples.getBuffer();
        int frames = samples.getBufferLength() / frameChannels;
        int bytes = frames * mChannelCount * 2;
        if (output.remaining() < bytes) {
            output = outputBuffer.grow(output.position() + bytes);
        }
        for (int i = 0; i < frames; i++) {
            if (frameChannels == mChannelCount) {
                for (int c = 0; c < mChannelCount; c++) {
                    output.putShort(pcm[i * frameChannels + c]);
                }
            } else if (frameChannels == 1) {
                // Mono to stereo (or more): the same sample on each channel.
                short sample = pcm[i];
                for (int c = 0; c < mChannelCount; c++) {
                    output.putShort(sample);
                }
            } else {
                // Stereo to mono: the average of both.
                output.putShort((short) ((pcm[i * 2] + pcm[i * 2 + 1]) / 2));
                for (int c = 1; c < mChannelCount; c++) {
                    output.putShort((short) 0);
                }
            }
        }
        return output;
    }
}
