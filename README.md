# Mail-Multiversum

Eigene Android-App für Posteo-Mails – im gleichen Stil und mit dem gleichen Update-Weg wie TCG Multiversum und Familien-Multiversum.

## Sicherheit
- **Direkt zu Posteo:** IMAP `posteo.de:993` und SMTP `posteo.de:465`, nur SSL/TLS, mit Prüfung von Zertifikat und Hostname. Kein Server dazwischen.
- **Passwort:** wird mit einem Schlüssel aus dem **Android Keystore** verschlüsselt (AES-256-GCM) und nur auf dem Handy gespeichert. Die Oberfläche bekommt es nach dem Anmelden nie wieder zu sehen. Keine Cloud-Sicherung der App-Daten.
- **HTML-Mails:** werden bereinigt und in einem abgeschotteten Rahmen **ohne JavaScript** angezeigt (Sandbox + Content-Security-Policy).
- **Externe Bilder** (oft Tracking-Pixel) sind blockiert, bis du sie freigibst – einmalig oder dauerhaft pro Absender.
- **Links** zeigen vor dem Öffnen ihr echtes Ziel und warnen, wenn Linktext und Ziel nicht zusammenpassen (Phishing).
- **Anhänge** werden nie automatisch geöffnet.
- **Benachrichtigungen** ohne Google-Push: Die App fragt selbst regelmäßig bei Posteo nach (WorkManager, mind. alle 15 Minuten).
- Im Repo liegen **keine** Zugangsdaten – deshalb darf es öffentlich sein (nötig für die Updates).

## Funktionen (Version 1)
- Anmeldung mit Posteo-Adresse und Passwort
- Posteingang und alle Ordner mit Ungelesen-Zählern, „Ältere laden“
- Mail lesen (HTML oder Text), Anhänge öffnen / speichern (Download/Mail-Multiversum) / teilen
- Antworten, Allen antworten, Weiterleiten (inkl. Original-Anhänge), neue Mail mit Anhängen
- Kopie landet automatisch in „Gesendet“, Original wird als beantwortet markiert
- Markieren (Stern), gelesen/ungelesen, verschieben, Spam, löschen (Papierkorb), Papierkorb/Spam leeren
- Mehrfachauswahl per langem Drücken
- Suche nach Betreff, Absender, Empfänger
- Absendername und Signatur, Adressvorschläge
- Automatische Update-Prüfung

## Aufbau
- `mail.html` – die Oberfläche (HTML/CSS/JS). Im Browser geöffnet läuft sie mit Beispieldaten zum Testen.
- `native/` – Java-Teil für Android: `MailPlugin` (IMAP/SMTP mit JavaMail), `SecureStore` (Keystore), `MailCheckWorker` (Hintergrund-Abruf), `MailCore` (Verbindungseinstellungen)
- `assets/icon.svg` – App-Icon
- `.github/workflows/build-apk.yml` – baut bei jeder Änderung die APK (Capacitor), signiert sie immer mit `signing/debug.keystore`, veröffentlicht sie als Release und schreibt `app/version.json`

## Hinweis Zwei-Faktor
Die Zwei-Faktor-Anmeldung von Posteo gilt nur für die Weboberfläche. In Mailprogrammen (und in dieser App) meldest du dich mit dem normalen Passwort an.
