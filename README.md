# AA Service

Android app for controlling Advantage Air MyAir5 HVAC systems via USB UART.

This is a clean-room reconstruction of the proprietary "AA Service" app (v14.150, package `com.air.advantage.aaservice`) from decompiled source. The original app communicates with MyAir5 units over an FTDI USB UART bridge using Android USB Accessory mode with a custom binary XML frame protocol.

## Features

- USB UART bridge via Android USB Accessory mode to MyAir5 HVAC controller
- Real-time bidirectional XML frame protocol with CRC-8 integrity
- Poll cycle for system data, zone temperatures, clock sync
- CAN message queue (up to 25 concurrent IDs) with retry and ack/nack handling
- Foreground service for persistent UART connection lifecycle
- Secure inter-app broadcasts via signature permission + AES encryption
- System data cache and state management
- Admin device policy and OTA reboot notification
- Alert/intent bridge between system broadcasts and UI

## Architecture

Layered Clean Architecture with unidirectional dependency: **UI → Service → Domain → Data**.

```
┌─────────────────────────────────────────────────────┐
│  UI Layer                                           │
│  MainActivity · AlertActivity · UsbConnectActivity  │
│  MainViewModel · AlertViewModel                     │
├─────────────────────────────────────────────────────┤
│  Service Layer                                      │
│  UartForegroundService · RebootNotificationService  │
├─────────────────────────────────────────────────────┤
│  Domain Layer                                       │
│  SystemData · ZoneData · CanMessage · Frame types   │
│  UartStateMachine · PollAllDataUseCase              │
├─────────────────────────────────────────────────────┤
│  Data Layer                                         │
│  UartDataSource · FrameParser · CrcCalculator       │
│  MessageReceiver · DataBroadcaster                  │
│  DataCacheRepository · PollQueueRepository          │
│  PreferencesManager                                 │
└─────────────────────────────────────────────────────┘
```

### Package Structure

```
com.air.advantage.aaservice/
├── data/
│   ├── uart/          — UART transport (UsbAccessoryDataSource, MockUartDataSource)
│   ├── protocol/      — FrameParser, CrcCalculator (pure Kotlin, no Android deps)
│   ├── broadcast/     — MessageReceiver, DataBroadcaster (inter-app broadcast routing)
│   ├── repository/    — DataCacheRepository, CanStateRepository, PollQueueRepository
│   └── prefs/         — PreferencesManager
├── domain/
│   ├── model/         — SystemData, ZoneData, ScheduleData, CanMessage, Frame types
│   ├── usecase/       — PollAllDataUseCase, SendCanMessageUseCase, GetDataUseCase
│   └── state/         — UartStateMachine (sealed class state machine)
├── service/
│   ├── UartForegroundService — Main foreground service (coroutine-based)
│   └── RebootNotificationService
├── ui/
│   ├── main/          — MainActivity + MainViewModel
│   ├── alert/         — AlertActivity + AlertViewModel
│   └── usb/           — UsbConnectActivity
├── receiver/
│   ├── DeviceAdminReceiver
│   ├── PackageUpgradeReceiver
│   ├── AlertDialogReceiver
│   ├── UsbPermissionReceiver
│   └── data message receivers
├── di/
│   └── AppModule, ServiceModule, UartModule
├── util/
│   ├── HardwareDetector — Always returns MyAir5
│   ├── CryptoHelper    — AES-128-CBC encryption
│   └── Constants
└── AAServiceApp.kt — Application class
```

## Tech Stack

| Component | Choice |
|-----------|--------|
| Language | Kotlin 1.9.20 |
| Minimum SDK | 19 (Android 4.4 KitKat) |
| Target SDK | 34 (Android 14) |
| Build | Gradle 8.5 + Android Gradle Plugin 8.2.0 |
| Async | Kotlin Coroutines 1.7.3 + Flow |
| DI | Dagger Hilt 2.48 |
| UI | XML layouts with ViewBinding |
| USB | Android USB Accessory API |
| Crypto | `javax.crypto` AES/CBC/PKCS7Padding |

## Prerequisites

- JDK 17
- Android SDK 34 (`platforms;android-34` + `build-tools;34.0.0`)
- Android Studio (recommended) or Gradle CLI

### SDK Setup

Set `ANDROID_SDK_ROOT` (or `ANDROID_HOME`) to your SDK path, or create `local.properties`:

```properties
sdk.dir=/path/to/android-sdk
```

## Build

```shell
# Debug APK
./gradlew assembleDebug

# Release APK
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## USB Protocol

The app communicates with MyAir5 via a custom frame protocol over USB serial.

### Frame Format

```
<U>payload</U=crc>
```

- Frames are wrapped in `<U>` and `</U=crc>` tags
- `crc` is a CRC-8 over the payload, computed via a 256-entry lookup table
- Maximum write chunk: 63 bytes, with 1ms sleep between chunks

### Control Markers

| Marker | Meaning |
|--------|---------|
| `<ack>1</ack>` | Acknowledgment |
| `<ack>0</ack>` | Negative acknowledgment |
| `<request>Unknown</request>` | Request marker |
| `getCAN` | CAN message fetch |

### Initialization

On connect, the app sends a 8-byte config packet:
```
[0x00, 0xE1, 0x00, 0x00, 0x08, 0x01, 0x00, 0x00]
```

### Poll Cycle (MyAir5)

1. `getSystemData` — injects `type=17`, `AppStore=MyAir5`, `MyAppRev=14.150`
2. `getClock` — synchronize system clock
3. `getZoneData?zone=1` through `zone=10` — per-zone temperature queries
4. Schedule polling is skipped for MyAir5

### CAN Message Queue

- Up to 25 CAN IDs tracked in the queue
- Each message retried up to 3 times on ack failure
- `Mutex`-protected access replaces `synchronized` blocks
- "CAN2 in use" response sets a busy flag
- Dual transmission path: secure (signature permission) + encrypted (for non-signature receivers)

## State Machine

```
Connecting → ConfigSent → Polling ↔ AwaitingResponse
```

- `Connecting` — USB accessory attached, initializing UART stream
- `ConfigSent` — 8-byte config packet sent, waiting for ack
- `Polling` — Idle state, ready to send next request
- `AwaitingResponse` — Request sent, waiting for response frame

Transitions are managed via a sealed class `UartState` with coroutine-based concurrency.

## Development Status

| Issue | Status |
|-------|--------|
| #1 Epic | 🔵 Open |
| #2 Project Scaffold | ✅ Complete |
| #3 Frame Protocol Layer | 📋 Planned |
| #4 Domain Models & State Machine | 📋 Planned |
| #5 Data Repositories | 📋 Planned |
| #6 Utility Package | 📋 Planned |
| #7 Broadcast Receivers | 📋 Planned |
| #8 Core UART Foreground Service | 📋 Planned |
| #9 Reboot & Device Admin | 📋 Planned |
| #10 UI Screens | 📋 Planned |
| #11 App Wiring & DI | 📋 Planned |

## License

Proprietary — reconstructed from decompiled source for reference/educational purposes.
