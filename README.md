# Bajaj Pulsar N160 Ride Connect

An experimental Android app for exploring direct Bluetooth Low Energy (BLE) communication with a Bajaj Pulsar N160 instrument cluster. The project implements the device-side BLE connection and packet formatting locally on the phone; it does not depend on an online account or a cloud service.

> **Prototype status:** This is a development and protocol-testing app, not a finished companion app. Many of its call, alert, contact, and navigation controls send sample or manually entered data. Use it with a compatible cluster and at your own discretion.

## What it does

- Scans for BLE devices and displays their names, addresses, signal strength, and paired status.
- Connects to a selected device or attempts to connect to a paired Pulsar device.
- Discovers the Pulsar BLE service and its characteristics, maintains a one-second general-status heartbeat, and serializes GATT operations through a queue.
- Sends test packets for cluster battery and signal indicators, incoming and ended calls, missed calls, notifications, turn-by-turn navigation, favorite contacts, and recent calls.
- Reads handlebar control packets while a simulated incoming or active call is in progress. A changed accept toggle updates the cluster call state; a changed reject toggle ends the simulated call.
- Displays connection state, cluster indicators, call state, the latest handlebar event, and a rolling debug log.

The app does **not** currently obtain routes from Google Maps or another navigation provider, listen for real phone calls or notifications, read the phone's contacts or call history, or send messages. Navigation and communication data are supplied by the app's test controls.

## Requirements

- An Android phone or emulator running Android 12 (API 32) or later.
- BLE hardware and a compatible Bajaj Pulsar N160 cluster for end-to-end testing. The app declares BLE hardware as required.
- Android SDK platform 37 to compile the app, as configured in `app/build.gradle.kts`.
- Android Studio and a compatible JDK/Gradle setup when the repository's build configuration is complete.

## Build setup status

The checked-in project currently has incomplete Gradle bootstrap files: `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, and `gradle/libs.versions.toml` are not present. The build scripts use the Gradle wrapper and version-catalog aliases, so Gradle sync and `./gradlew` commands cannot run from this checkout until those files are restored or supplied.

Once the wrapper and version catalog are available:

```bash
# Run local JVM unit tests
./gradlew testDebugUnitTest

# Build the debug APK
./gradlew assembleDebug
```

The application module's current configuration is:

| Setting | Value |
| --- | --- |
| Application ID | `com.anishudupan.bajarpulsarn160` |
| Minimum Android version | API 32 |
| Compile SDK | API 37 |
| Target SDK | API 37 |
| App version | 1.0 (version code 1) |
| Java source/target compatibility | Java 11 |

## Connect and try the controls

1. Enable Bluetooth on the Android device. Pair the phone with the instrument cluster in Android Bluetooth settings if the cluster requires pairing.
2. Install and launch the app.
3. Grant the requested Bluetooth and location permissions when prompted. On Android 12 and later, BLE scanning and connection use the Nearby devices permissions (`BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`); this app also requests coarse and fine location permissions.
4. Tap **Scan BLE** and select the cluster from the discovered-device list, or tap **Connect to Paired PULSAR** to try a previously paired device.
5. Wait for the status to show `CONNECTED`. The app discovers the cluster's GATT service and starts sending its general-status heartbeat.
6. Use the controls to send test data. Values may be entered in the form or come from built-in sample records. Disconnect when finished.

The controls are a protocol demonstrator. An operation initiated while disconnected may be logged as simulated rather than delivered to a cluster. A successful BLE connection also depends on the target advertising or exposing the expected service and characteristics.

## BLE protocol implementation

Packet construction and UUID definitions are centralized in `PulsarProtocol.kt`; BLE scanning, connection, GATT setup, queued reads/writes, heartbeat, and control polling are in `PulsarBleManager.kt`.

The implementation uses a custom service UUID and characteristic UUIDs for general status, navigation, alerts, missed calls, favorite contacts, control events, and recent calls. The implemented V1 packet builders include:

| Packet | Size | Purpose |
| --- | ---: | --- |
| General status | 55 bytes | Call state, manually selected cluster battery/signal bars, missed-call/SMS counts, caller display, acknowledgements, and heartbeat |
| Missed call | 74 bytes | Caller name and phone number |
| Alert | 40 bytes | Alert type and message |
| Turn-by-turn navigation | 48 bytes | Maneuver, distance/unit, active flag, and street name |
| Favorite contacts | 151 bytes | Up to five contact slots |
| Recent calls | 151 bytes | Up to five call slots and call-status values |
| Control input | At least 20 bytes | Parsed for volume/dial fields and handlebar call toggles |

The connection handshake requests an MTU of 256 before service discovery. General-status frames are sent once per second while connected. Writes and reads are serialized through a queue because GATT operations must be coordinated; control reads are polled while an incoming or active call is being simulated.

The app's battery monitor reads the **phone's** battery percentage for diagnostics. The battery-bar value sent in the cluster status packet is a separate manual setting in the app.

## Project layout

```text
app/src/main/
├── AndroidManifest.xml
├── java/com/anishudupan/bajarpulsarn160/
│   ├── MainActivity.kt
│   ├── FirstFragment.kt              # Connection screen and test controls
│   ├── SecondFragment.kt
│   ├── battery/BatteryMonitor.kt     # Phone battery diagnostics
│   ├── ble/PulsarBleManager.kt       # BLE lifecycle and GATT operations
│   ├── protocol/PulsarProtocol.kt    # UUIDs, packet builders, control parser
│   └── ui/DeviceAdapter.kt           # Discovered-device list
└── res/                              # Layouts, navigation, themes, icons
```

## Tests

`app/src/test/java/com/anishudupan/bajarpulsarn160/PulsarProtocolUnitTest.kt` contains JVM unit tests for the general-status, missed-call, alert, navigation, favorite-contact, and recent-call packet builders. Run them with `./gradlew testDebugUnitTest` after the missing Gradle wrapper and version catalog files have been restored.

## Permissions and data handling

The manifest requests Bluetooth permissions and coarse/fine location permissions for BLE discovery, and the app requests them at runtime. It does not declare the Android `INTERNET` permission or implement an online service. Names, numbers, and alert text entered into the test controls are serialized into BLE packets when connected to a compatible device.
