# h264opusServer

Kotlin + Jetpack Compose + Material3 single-Activity app that listens on local TCP ports:

- Video stream: raw H264 Annex-B (`0x00000001` start code) on port `27183` (default)
- Audio stream: raw Opus frames on port `27184` (default)

## Build

```bash
./gradlew assembleDebug
```

If Android SDK is missing, Gradle will fail with a message similar to `SDK location not found`.
Please install Android SDK (API 34 + Build-Tools) and set one of:

- `ANDROID_HOME`
- `ANDROID_SDK_ROOT`
- `local.properties` with `sdk.dir=/path/to/Android/Sdk`

## Runtime behavior

- App starts listening automatically.
- Use top-right Settings icon to change ports.
- Video keeps original aspect ratio (fit center, no stretching).
- On Activity disposal all sockets/decoders/audio track are released.
