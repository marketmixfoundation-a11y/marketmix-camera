# MarketMix Camera

An Android camera that blurs the background behind the subject **while you record** and bakes that blur into the saved video. The subject stays sharp; everything else is blurred — no green screen, no cloud, no subscription.

- Live subject detection on the device (MediaPipe selfie segmenter, TFLite)
- Real-time GPU blur compositor (OpenGL ES 2.0)
- The recorded file already contains the effect — no editing step
- Output keeps the camera's original 16:9 framing, rotated to portrait 720×1280 (no stretching, no cropping)
- Audio is recorded and muxed into the same file
- Videos land in `Movies/MarketMix Camera`

## Download

Get the APK from the [Releases page](../../releases/latest):

1. Download `MarketMix-Camera-0.1.0.apk` on your phone.
2. Open it and allow "install unknown apps" when Android asks.
3. Install and launch **MarketMix Camera**.

Requires Android 10 (API 29) or newer and a camera with OpenGL ES 2.0 (any phone from the last several years).

## How to use

| Control | What it does |
| --- | --- |
| Big red button | Start / stop recording |
| **Blur** slider | Background blur strength |
| **Invert subject** | Blur you instead of the background (good for product shots) |
| **Flip** | Switch between front and rear camera |

The front camera preview is mirrored so it behaves like a mirror — and the saved clip matches what you saw.

## How it works

1. CameraX delivers 1280×720 RGBA frames.
2. Every frame is downscaled to 256×256 and run through the bundled selfie segmenter to produce a person mask, smoothed over time.
3. The GL compositor renders three passes: the sharp frame, a blurred copy, and a mix of the two using the mask. The result is drawn to the screen and to a MediaCodec input surface at the same time.
4. H.264 video from the encoder and AAC audio from the microphone are muxed, then published to the gallery through MediaStore.

Because the mask and the camera frame are both 2D bitmaps in the same coordinate space, the cut-out lines up with the subject exactly.

Everything runs on the device. The app has no network permission.

## Build from source

Requirements: JDK 17, Android SDK with platform 35.

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press Run.

## License

MIT — see [LICENSE](LICENSE). The bundled `selfie_segmenter.tflite` model comes from [MediaPipe](https://github.com/google-ai-edge/mediapipe) (Apache 2.0).
