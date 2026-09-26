# Live Channels Extended

Extended version of [AOSP Live TV Kotlin](https://github.com/Gravarty/AOSP-Live-TV-Kotlin).
New features are marked with `Extended:` in the code, their strings live in `strings-extended.xml`.

## New in Extended
- **Source:** Tile in the TV options. Splits the channel list by tuner/service (e.g. DVB tuner, HTS).
  Channel up/down, program guide, recent channels, channel list and search only show the selected source.
  Switching tunes the last watched channel of that source. If a source disappears, the app switches automatically.

## Base

Kotlin port of the AOSP app **Live Channels** (Live TV), 1:1 based on
[LineageOS/android_packages_apps_TV](https://github.com/LineageOS/android_packages_apps_TV) (branch `lineage-21.0`).
The base contains no features of its own. Every deviation from the original is commented in the code, fixed bugs are marked with `Bugfix:`.

Tested on JVC/Vestel (MediaTek), Android 14, with a DVB tuner and a custom TV input app.

### Modernized
- Java → Kotlin, Gradle build (AGP 8.7, Kotlin 2.1), minSdk 30, compileSdk/targetSdk 35
- Runs as a regular app (no system app required), app ID `com.android.tv`
- Hilt instead of manual singletons, AsyncTask → Coroutines, Guava futures → own futures
- AndroidX fragments and Leanback `*SupportFragment` instead of the old framework fragments
- AutoValue → Kotlin `data class`

### Removed (require system permissions or missing from the SDK)
Parental controls/content ratings, HDMI-CEC, system properties, TvProvider search, built-in tuner (JNI),
cloud EPG, analytics, developer options. Channel lock with PIN and DVR are kept.

### Fixed for MediaTek TVs (verified by logs)
- **App always jumped to setup:** Without system permissions all channels were treated as hidden.
- **Audio kept dropping (subtitles):** The tuner reports "no subtitle" as track `255`, the app deselected it endlessly. On top of that "subtitles off" was sent several times per second.
- **Audio stuttering (audio track):** The tuner reports channel count/language only for the active track, so the automatic selection kept jumping between two tracks.
- **Tuner lost after using the TV's own player:** After the tuner reported "no subtitle", the app sent "deselect audio track" although none was selected. The MediaTek tuner service crashed on that (`a_mtktvapi_select_audio`), playback kept buffering.

### More bugs fixed from the original (selection)
- Many crashes (NPE) on missing channels, programs, inputs or recordings, mainly in DVR, program guide and menu
- DVR: recordings of removed inputs were deleted with the wrong ID, changed end times were never applied, deleting a series stopped only the first recording, endless recursion in the conflict dialog
- DVR lists: rows ended up before their header, rows were skipped when removing
- Timeshift: "jump forward" was never disabled
- Date format not thread-safe, division by zero on key repeat

All spots: `grep -rn "Bugfix" app/src/main/kotlin`

### Build
`./gradlew assembleRelease` (release is signed with the debug key for testing).
Always use the release build for performance tests, debug is much slower on TV devices.

### License
Apache 2.0, like the original (see `LICENSE`, `NOTICE`).
