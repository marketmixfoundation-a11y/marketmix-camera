# MarketMix Camera 0.1.0

First public build. Record video with the background blurred live — the effect is written into the file, so there is no editing step afterwards.

## What you get

- **Live subject blur** — the person stays sharp, the room behind them is blurred while you record
- **Baked-in effect** — the MP4 on your phone already looks like the preview
- **Original framing** — the camera's 16:9 view is rotated to a 720×1280 portrait clip, never stretched or cropped
- **Sound** — microphone audio is recorded and muxed into the same file
- **Controls** — blur strength slider, invert-subject toggle, front/rear flip
- **Offline** — segmentation runs on the device; the app has no network permission
- Videos are saved to `Movies/MarketMix Camera` and show up in your gallery

## Install

1. Download `MarketMix-Camera-0.1.0.apk` on your Android phone.
2. Open the file. Android will ask you to allow installing from this source — turn it on for your browser/file manager.
3. Tap **Install**, then open **MarketMix Camera** and allow camera and microphone access.

Requires Android 10 or newer.

## Notes

- The preview is letterboxed to keep the true aspect ratio; your recordings match it.
- Front-camera preview is mirrored like a mirror, and the saved clip matches what you saw.
- The APK is signed for sideloading (not distributed through Google Play).
- Source code and build instructions: see the repository README.
