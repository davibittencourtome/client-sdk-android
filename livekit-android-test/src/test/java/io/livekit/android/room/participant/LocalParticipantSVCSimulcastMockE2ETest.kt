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

package io.livekit.android.room.participant

import io.livekit.android.room.DefaultsManager
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoCodec
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.MockEglBase
import io.livekit.android.test.mock.MockRTCThreadToken
import io.livekit.android.test.mock.MockVideoCapturer
import io.livekit.android.test.mock.MockVideoStreamTrack
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.util.toPBByteString
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import livekit.LivekitModels.VideoLayer
import livekit.LivekitModels.VideoQuality
import livekit.LivekitRtc
import livekit.LivekitRtc.SubscribedCodec
import livekit.LivekitRtc.SubscribedQuality
import livekit.org.webrtc.RtpParameters
import livekit.org.webrtc.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

/**
 * VP9 published as rid based simulcast with one spatial layer per stream (L1T3 on every
 * layer), so the server has smaller versions of the video for subscribers with little
 * bandwidth. Parity with livekit-client (`isSVCSimulcast`, server newer than v1.13.6).
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class LocalParticipantSVCSimulcastMockE2ETest : MockE2ETest() {

    private fun joinWithServerVersion(version: String): LivekitRtc.SignalResponse {
        return with(TestData.JOIN.toBuilder()) {
            join = join.toBuilder()
                .setServerVersion(version)
                .setServerInfo(join.serverInfo.toBuilder().setVersion(version).build())
                .build()
            build()
        }
    }

    private fun useVP9(scalabilityMode: String = "L1T3", simulcast: Boolean = true) {
        room.videoTrackPublishDefaults = room.videoTrackPublishDefaults.copy(
            // An explicit encoding, as camera publishers usually set: without one and without
            // simulcast the SDK publishes no encodings at all, whatever the codec.
            videoEncoding = VideoEncoding(maxBitrate = 900_000, maxFps = 24),
            videoCodec = VideoCodec.VP9.codecName,
            scalabilityMode = scalabilityMode,
            simulcast = simulcast,
            backupCodec = BackupVideoCodec(codec = VideoCodec.VP8.codecName),
        )
    }

    private fun createLocalTrack(
        width: Int = 960,
        height: Int = 720,
        source: VideoSource = mock(VideoSource::class.java),
    ) = LocalVideoTrack(
        capturer = MockVideoCapturer(),
        source = source,
        name = "",
        options = LocalVideoTrackOptions(
            isScreencast = false,
            deviceId = null,
            position = null,
            captureParams = VideoCaptureParameter(width = width, height = height, maxFps = 24),
        ),
        rtcTrack = MockVideoStreamTrack(),
        peerConnectionFactory = component.peerConnectionFactory(),
        context = context,
        eglBase = MockEglBase(),
        defaultsManager = DefaultsManager(),
        trackFactory = mock(LocalVideoTrack.Factory::class.java),
        rtcThreadToken = MockRTCThreadToken(),
    )

    private fun sentAddTrackRequest(): LivekitRtc.AddTrackRequest {
        return wsFactory.ws.sentRequests
            .map { LivekitRtc.SignalRequest.newBuilder().mergeFrom(it.toPBByteString()).build() }
            .single { it.hasAddTrack() }
            .addTrack
    }

    private fun publishedEncodings(): List<RtpParameters.Encoding> {
        return getPublisherPeerConnection().transceivers.first().sender.parameters.encodings
    }

    @Test
    fun publishesVP9AsSimulcastWhenServerSupportsIt() = runTest {
        useVP9()
        connect(joinWithServerVersion("1.13.11"))
        wsFactory.ws.clearRequests()

        room.localParticipant.publishVideoTrack(track = createLocalTrack(width = 960, height = 720))
        testScheduler.advanceUntilIdle()

        val addTrack = sentAddTrackRequest()
        assertEquals("vp9", addTrack.simulcastCodecsList[0].codec)
        assertEquals(VideoLayer.Mode.ONE_SPATIAL_LAYER_PER_STREAM, addTrack.simulcastCodecsList[0].videoLayerMode)
        // The backup codec is plain simulcast and does not declare a layer mode.
        assertEquals("vp8", addTrack.simulcastCodecsList[1].codec)
        assertEquals(VideoLayer.Mode.MODE_UNUSED, addTrack.simulcastCodecsList[1].videoLayerMode)

        val layers = addTrack.layersList.sortedBy { it.width }
        assertEquals(listOf(240, 480, 960), layers.map { it.width })
        assertEquals(listOf(180, 360, 720), layers.map { it.height })
        assertEquals(listOf(VideoQuality.LOW, VideoQuality.MEDIUM, VideoQuality.HIGH), layers.map { it.quality })

        val encodings = publishedEncodings()
        assertEquals(listOf("f", "h", "q"), encodings.map { it.rid })
        assertEquals(listOf(1.0, 2.0, 4.0), encodings.map { it.scaleResolutionDownBy })
        assertTrue(encodings.all { it.scalabilityMode == "L1T3" })
        assertTrue(encodings.all { it.numTemporalLayers == null })
    }

    @Test
    fun publishesTwoVersionsBelow960() = runTest {
        useVP9()
        connect(joinWithServerVersion("1.13.11"))
        wsFactory.ws.clearRequests()

        room.localParticipant.publishVideoTrack(track = createLocalTrack(width = 640, height = 480))
        testScheduler.advanceUntilIdle()

        val layers = sentAddTrackRequest().layersList.sortedBy { it.width }
        assertEquals(listOf(240, 640), layers.map { it.width })
        // rids are handed out from the smallest layer up (q, h, f), as for VP8 simulcast,
        // so with two layers the full resolution one is "h".
        assertEquals(listOf(VideoQuality.LOW, VideoQuality.MEDIUM), layers.map { it.quality })
        val encodings = publishedEncodings()
        assertEquals(listOf("h", "q"), encodings.map { it.rid })
        assertTrue(encodings.all { it.scalabilityMode == "L1T3" })
    }

    @Test
    fun keepsVP9AsSVCWhenServerDoesNotSupportSimulcast() = runTest {
        useVP9()
        // Last server version without support for VP9/AV1 simulcast.
        connect(joinWithServerVersion("1.13.6"))
        wsFactory.ws.clearRequests()

        room.localParticipant.publishVideoTrack(track = createLocalTrack(width = 960, height = 720))
        testScheduler.advanceUntilIdle()

        val addTrack = sentAddTrackRequest()
        assertEquals(VideoLayer.Mode.MODE_UNUSED, addTrack.simulcastCodecsList[0].videoLayerMode)
        assertEquals(1, addTrack.layersCount)
        assertEquals(VideoQuality.HIGH, addTrack.layersList[0].quality)
        assertEquals(960, addTrack.layersList[0].width)

        val encodings = publishedEncodings()
        assertEquals(1, encodings.size)
        assertTrue(encodings[0].rid.isNullOrEmpty())
        assertEquals("L1T3", encodings[0].scalabilityMode)
    }

    @Test
    fun keepsVP9AsSVCWhenServerVersionIsUnknown() = runTest {
        useVP9()
        connect(joinWithServerVersion("1.9.3"))
        wsFactory.ws.clearRequests()

        room.localParticipant.publishVideoTrack(track = createLocalTrack(width = 960, height = 720))
        testScheduler.advanceUntilIdle()

        assertEquals(VideoLayer.Mode.MODE_UNUSED, sentAddTrackRequest().simulcastCodecsList[0].videoLayerMode)
        assertEquals(1, publishedEncodings().size)
    }

    @Test
    fun keepsMultipleSpatialLayerModesAsSVC() = runTest {
        // Simulcast only applies to single spatial layer modes (L1T*); L3T3_KEY stays SVC.
        useVP9(scalabilityMode = "L3T3_KEY")
        connect(joinWithServerVersion("1.13.11"))
        wsFactory.ws.clearRequests()

        room.localParticipant.publishVideoTrack(track = createLocalTrack(width = 960, height = 720))
        testScheduler.advanceUntilIdle()

        val addTrack = sentAddTrackRequest()
        assertEquals(VideoLayer.Mode.MODE_UNUSED, addTrack.simulcastCodecsList[0].videoLayerMode)
        val encodings = publishedEncodings()
        assertEquals(1, encodings.size)
        assertEquals("L3T3_KEY", encodings[0].scalabilityMode)
    }

    @Test
    fun keepsVP9AsSVCWhenSimulcastIsDisabled() = runTest {
        useVP9(simulcast = false)
        connect(joinWithServerVersion("1.13.11"))
        wsFactory.ws.clearRequests()

        room.localParticipant.publishVideoTrack(track = createLocalTrack(width = 960, height = 720))
        testScheduler.advanceUntilIdle()

        assertEquals(VideoLayer.Mode.MODE_UNUSED, sentAddTrackRequest().simulcastCodecsList[0].videoLayerMode)
        val encodings = publishedEncodings()
        assertEquals(1, encodings.size)
        assertEquals("L1T3", encodings[0].scalabilityMode)
    }

    @Test
    fun dynacastPausesEachSimulcastLayer() = runTest {
        useVP9()
        connect(joinWithServerVersion("1.13.11"))

        room.localParticipant.publishVideoTrack(track = createLocalTrack(width = 960, height = 720))
        testScheduler.advanceUntilIdle()

        // The only subscriber wants the medium version: the high one stops, the others keep going.
        receiveVP9Qualities(
            trackSid = room.localParticipant.videoTrackPublications.first().first.sid,
            enabled = mapOf(
                VideoQuality.HIGH to false,
                VideoQuality.MEDIUM to true,
                VideoQuality.LOW to true,
            ),
        )
        testScheduler.advanceUntilIdle()

        val encodings = publishedEncodings().associateBy { it.rid }
        assertEquals(setOf("f", "h", "q"), encodings.keys)
        assertFalse(encodings.getValue("f").active)
        assertTrue(encodings.getValue("h").active)
        assertTrue(encodings.getValue("q").active)
    }

    private fun receiveVP9Qualities(trackSid: String, enabled: Map<VideoQuality, Boolean>) {
        wsFactory.receiveMessage(
            with(LivekitRtc.SignalResponse.newBuilder()) {
                subscribedQualityUpdate = with(LivekitRtc.SubscribedQualityUpdate.newBuilder()) {
                    this.trackSid = trackSid
                    addSubscribedCodecs(
                        with(SubscribedCodec.newBuilder()) {
                            codec = VideoCodec.VP9.codecName
                            addAllQualities(
                                enabled.map { (quality, on) ->
                                    SubscribedQuality.newBuilder()
                                        .setQuality(quality)
                                        .setEnabled(on)
                                        .build()
                                },
                            )
                            build()
                        },
                    )
                    build()
                }
                build().toOkioByteString()
            },
        )
    }
}
