# XCVario G-Analyzer

Android-App zur Offline-Auswertung von XCVario-NMEA-Dateien. Liest die letzten drei numerischen Felder aus jedem `$PXCV`-Datensatz als X/Y/Z und berechnet `Nz = -Z`.

Die Zeitachse wird mangels PXCV-Zeitstempel aus einem einstellbaren Abtastintervall rekonstruiert (Standard 0,20 s / 5 Hz). ASK-21-Werte sind editierbare Startwerte und müssen vor jeder flugbetrieblichen Verwendung mit dem gültigen Flughandbuch der konkreten Variante abgeglichen werden. Keine zugelassene Strukturüberwachung.

## APK bauen

GitHub Actions > Build Android APK > Run workflow. Anschließend das Artifact `XCVarioGAnalyzer-debug-apk` herunterladen und `app-debug.apk` installieren.