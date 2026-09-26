# Live Channels Extended

Erweiterte Version von [AOSP Live TV Kotlin](https://github.com/Gravarty/AOSP-Live-TV-Kotlin).
Neue Funktionen sind im Code mit `Extended:` markiert, Texte liegen in `strings-extended.xml`.

## Neu in Extended
- **Quelle:** Tile in den TV-Optionen. Trennt die Kanalliste nach Tuner/Service (z. B. DVB-Tuner, HTS).
  Umschalten, Programmübersicht, zuletzt gesehene Kanäle, Kanalliste und Suche zeigen nur die gewählte Quelle.
  Beim Wechsel wird der zuletzt gesehene Kanal der Quelle getunt. Fällt eine Quelle weg, wechselt die App automatisch.

## Basis

Kotlin-Portierung der AOSP-App **Live Channels** (Live TV), 1:1 nach dem Original
[LineageOS/android_packages_apps_TV](https://github.com/LineageOS/android_packages_apps_TV) (Branch `lineage-21.0`).
Die Basis enthält keine eigenen Features. Jede Abweichung vom Original ist im Code kommentiert, behobene Fehler sind mit `Bugfix:` markiert.

Getestet auf JVC/Vestel (MediaTek), Android 14, mit DVB-Tuner und einer eigenen TV-Input-App.

### Modernisiert
- Java → Kotlin, Build mit Gradle (AGP 8.7, Kotlin 2.1), minSdk 30, compileSdk/targetSdk 35
- Läuft als normale App (keine System-App nötig), App-ID `com.android.tv`
- Hilt statt manueller Singletons, AsyncTask → Coroutines, Guava-Futures → eigene Futures
- AndroidX-Fragments und Leanback-`*SupportFragment` statt der alten Framework-Fragments
- AutoValue → Kotlin-`data class`

### Entfernt (brauchen Systemrechte oder fehlen im SDK)
Kindersicherung/Altersfreigaben, HDMI-CEC, System-Properties, TvProvider-Suche, eingebauter Tuner (JNI),
Cloud-EPG, Analytics, Entwickleroptionen. Kanalsperre mit PIN und DVR bleiben erhalten.

### Behoben für MediaTek-Fernseher (per Log belegt)
- **App sprang immer ins Setup:** Ohne Systemrechte galten alle Kanäle als ausgeblendet.
- **Ton setzt ständig aus (Untertitel):** Der Tuner meldet „kein Untertitel“ als Spur `255`, die App wählte sie endlos ab. Dazu wurde „Untertitel aus“ mehrmals pro Sekunde gesendet.
- **Ton stottert (Tonspur):** Der Tuner meldet Kanalzahl/Sprache nur für die aktive Spur, die automatische Wahl sprang dadurch endlos zwischen zwei Spuren.

### Weitere behobene Fehler aus dem Original (Auswahl)
- Zahlreiche Abstürze (NPE) bei fehlenden Kanälen, Sendungen, Inputs oder Aufnahmen, v. a. in DVR, Programmführer und Menü
- DVR: Aufnahmen entfernter Inputs wurden mit der falschen ID gelöscht, geänderte Endzeiten nie übernommen, beim Löschen einer Serie nur die erste Aufnahme gestoppt, endlose Rekursion im Konfliktdialog
- DVR-Listen: Zeilen landeten vor ihrer Überschrift, beim Entfernen wurden Zeilen übersprungen
- Timeshift: „Weiter springen“ wurde nie deaktiviert
- Datumsformat nicht threadsicher, Division durch 0 bei Tastenwiederholung

Alle Stellen: `grep -rn "Bugfix" app/src/main/kotlin`

## Bauen
`./gradlew assembleRelease` (Release ist zum Testen mit dem Debug-Schlüssel signiert).
Für Leistungstests immer den Release-Build nehmen, Debug ist auf TV-Geräten deutlich langsamer.

## Lizenz
Apache 2.0, wie das Original (siehe `LICENSE`, `NOTICE`).
