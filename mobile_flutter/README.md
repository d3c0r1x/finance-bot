# FinPulse Flutter

Flutter client for Android and Web/PWA.

This folder is intentionally separate from the current Kotlin prototype. The
Kotlin app stays buildable while Flutter becomes the shared Android/iPhone-Web
client.

## Targets

- Android APK for the owner.
- Web/PWA for iPhone Safari until a Mac/Xcode or cloud iOS builder exists.

## Default backend

`http://192.168.3.48:8000`

Change it on the login screen when using another network.

## Run after installing Flutter SDK

```bash
flutter pub get
flutter run -d chrome
flutter build apk --debug
flutter build web
```
