/*
 * Copyright 2026 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.room

import com.vdurmont.semver4j.Semver

/**
 * Whether a VP9/AV1 track should be published as rid based simulcast (one spatial
 * layer per stream) rather than as SVC.
 *
 * The caller opts in by asking for simulcast together with a single spatial layer
 * scalability mode (`L1T*`). Mirrors `isSVCSimulcast` in livekit-client.
 */
internal fun isSVCSimulcast(codec: String?, simulcast: Boolean, scalabilityMode: String?): Boolean {
    return isSVCCodec(codec) && simulcast && scalabilityMode?.startsWith("L1T", ignoreCase = true) == true
}

/**
 * Last server version that does not honour `SimulcastCodec.videoLayerMode`, i.e. that
 * cannot receive VP9/AV1 as rid based simulcast. Same value as livekit-client.
 */
internal const val SVC_SIMULCAST_LAST_UNSUPPORTED_SERVER_VERSION = "1.13.6"

/**
 * Whether the connected server accepts VP9/AV1 published as rid based simulcast.
 * An unknown version is treated as unsupported, so the publish falls back to SVC.
 */
internal fun isSVCSimulcastSupportedByServer(serverVersion: Semver?): Boolean {
    if (serverVersion == null) {
        return false
    }
    return serverVersion.isGreaterThan(SVC_SIMULCAST_LAST_UNSUPPORTED_SERVER_VERSION)
}
