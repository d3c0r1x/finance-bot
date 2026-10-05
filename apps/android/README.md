# Android local development

The emulator uses `localhost:8081` for Keycloak because its hosted login form
posts back to the same host that issued the login cookie. Before testing, forward
the Keycloak port from the emulator to the development host:

```powershell
adb reverse tcp:8081 tcp:8081
```

The Core API remains reachable at `10.0.2.2:8080`. Build and install the debug
APK with the Gradle wrapper, then launch Finance from the emulator.
