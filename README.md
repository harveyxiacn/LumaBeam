# LumaBeam · 光束投屏

Ad-free screen casting for Android TV. Turn any Android TV, Google TV or TV box into a receiver for
phones and tablets — Android, iPhone and iPad.

One APK does both jobs: on a TV it is the **receiver**, on a phone it is the **sender**.

| From | How | What you get |
|---|---|---|
| Android phone (app installed) | Open LumaBeam → tap the TV | Full screen + sound mirroring, low latency |
| iPhone / iPad / Android video apps | Tap the TV / cast / 投屏 button in the app | The video plays on the TV (DLNA); control it from the phone |
| Any phone or tablet, any browser | Scan the QR code on the TV | Send photos, videos, music or a link; web remote |

No ads, no accounts, no analytics, no internet access needed — everything stays on your LAN.
All code in this repository is original and uses only the Android SDK and the Kotlin standard library.

## Built for smooth, stable casting

- **Low latency mirroring** – hardware H.264 encode/decode, decoder low-latency mode, no B-frames,
  late frames are dropped instead of queued, audio buffer capped at ~250 ms.
- **Adaptive bitrate** – the sender watches its own send queue; when Wi-Fi is congested it skips to
  the next key frame and lowers the bitrate (instead of building up seconds of delay), then raises it
  again when the link recovers.
- **Auto-reconnect** – a Wi-Fi hiccup does not end the session: the phone keeps capturing and
  reconnects for up to a minute.
- **Stable links** – keep-alive heartbeats, TCP_NODELAY, DSCP real-time marking, Wi-Fi low-latency
  locks on both ends, rotation handled without reconnecting.
- **Starts with the TV** – the receiver runs as a foreground service and restarts after boot.

## Using it

**TV:** install the APK and open LumaBeam. The home screen shows the TV name, its address and a QR code.
To let casts appear while another app is open, use *Allow casts over other apps* (if your TV offers it).
Remote: OK = play/pause, ←/→ = seek 10 s, Back = stop.

**Android phone:** install the same APK. Pick a TV from the list (or type the address shown on the TV),
allow screen capture. Sound mirroring needs Android 10+. Choose *Smooth* on weak Wi-Fi.

**iPhone/iPad:** use the cast/投屏 button inside video apps, or the QR code web page.

### About AirPlay and Google Cast

Native iOS *Screen Mirroring* (AirPlay) requires Apple's proprietary FairPlay handshake, and receiving
Google Cast requires Google certification; neither can be implemented with original code, so LumaBeam
supports iOS through DLNA and the browser instead. Miracast needs system privileges that normal apps
don't get.

## Building

Requirements: JDK 17 and the Android SDK (platform 34).

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Without signing configuration the release APK is signed with the debug key. To sign with your own
key, create `keystore.properties` (not committed):

```properties
storeFile=keystore/lumabeam-release.jks
storePassword=…
keyAlias=lumabeam
keyPassword=…
```

GitHub Actions builds every push; pushing a tag `v*` publishes a release with the APK. CI signing uses
the secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
`SIGNING_KEY_PASSWORD`.

On an ARM64 Linux build host, point Gradle at a native aapt2 in `~/.gradle/gradle.properties`
(`android.aapt2FromMavenOverride=/path/to/aapt2`), since Google ships aapt2 for x86-64 only.

## How it works

```
app/src/main/java/com/lumabeam/
  MainActivity.kt          TV → receiver, phone → sender
  core/                    HTTP server, mirroring wire protocol, QR encoder, network helpers
  receiver/                TV: activity, foreground service, mirror server/decoder, web casting
  receiver/dlna/           UPnP MediaRenderer: SSDP, descriptions, SOAP control, GENA events
  sender/                  phone: screen/audio capture, encoder, adaptive send queue, discovery
```

Ports: 47800/TCP (mirroring), 47801/TCP (DLNA + web page), 1900/UDP (SSDP discovery).
Mirroring protocol: `"LMB1"` + JSON hello each way, then framed packets
(`type, flags, length, ptsUs, payload`) carrying H.264 Annex-B video and 48 kHz 16-bit PCM audio.

## License

MIT
