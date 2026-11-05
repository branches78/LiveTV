/*
 * Copyright 2026 The Android Open Source Project
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

package android.hardware.hdmi;

/** Stub of the @SystemApi class; see systemstubs/build.gradle. */
public class HdmiDeviceInfo {
    public static final int DEVICE_RECORDER = 1;
    public static final int DEVICE_PLAYBACK = 4;

    public int getDeviceType() {
        throw new RuntimeException("Stub!");
    }

    public boolean isCecDevice() {
        throw new RuntimeException("Stub!");
    }

    public boolean isMhlDevice() {
        throw new RuntimeException("Stub!");
    }
}
