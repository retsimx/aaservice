# AA Service Reconstruction — Approved Design

## Overview

Reconstruct the decompiled "AA Service" Android app (v14.150, package `com.air.advantage.aaservice`) as a modern Kotlin + Gradle project. The app communicates with Advantage Air MyAir5 HVAC systems via FTDI USB UART using a custom binary XML protocol.

**Platform:** Native Android (Kotlin, Gradle, minSdk 19, targetSdk 34)
**Hardware support:** MyAir5 only
**Excluded:** Firebase Crashlytics, Firebase Analytics, any bundled library code
**Dependencies:** Via Gradle (no bundled libs from decompiled APK)

## Architecture

Clean Architecture with 3 layers, using Kotlin Coroutines + Flow for async, Hilt for DI.

```
app/
├── data/
│   ├── uart/          — UartDataSource (UsbAccessoryDataSource / MockUartDataSource)
│   ├── protocol/      — FrameParser, CrcCalculator (pure Kotlin, no Android deps)
│   ├── broadcast/     — MessageReceiver, DataBroadcaster
│   ├── repository/    — DataCacheRepository, CanStateRepository, PollQueueRepository
│   └── prefs/         — PreferencesManager
├── domain/
│   ├── model/         — SystemData, ZoneData, ScheduleData, CanMessage, Frame types
│   ├── usecase/       — PollAllDataUseCase, SendCanMessageUseCase, GetDataUseCase, etc.
│   └── state/         — UartStateMachine (sealed class state machine)
├── service/
│   ├── UartForegroundService    — Main foreground service (coroutine-based)
│   └── RebootNotificationService
├── ui/
│   ├── MainActivity + MainViewModel
│   ├── AlertActivity + AlertViewModel
│   └── UsbConnectActivity
├── receiver/
│   ├── DeviceAdminReceiver
│   ├── PackageUpgradeReceiver
│   ├── AlertDialogReceiver
│   ├── UsbPermissionReceiver
│   └── 7× data message receivers
├── di/
│   ├── AppModule, ServiceModule, UartModule
├── util/
│   ├── HardwareDetector    — Always returns MyAir5
│   ├── CryptoHelper        — AES-128-CBC (replaces B.a)
│   └── Constants
└── AAServiceApp.kt
```

## Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | Kotlin | Modern Android standard, null safety, coroutines |
| Async | Coroutines + Flow | Replaces raw Thread/Handler/Executor |
| DI | Hilt | Standard Android DI, scoped service lifecycle |
| UI | XML layouts | Reuse decompiled layouts directly |
| USB mode | Android USB Accessory | Same as original — not FTDI D2XX driver |
| Crypto | `javax.crypto` AES/CBC/PKCS7Padding | Built into Android, no extra lib |
| Hardware detection | Always MyAir5 | Hardcoded — only target system |

## Obfuscated Dependency Replacements

| Original | Replacement | Notes |
|----------|-------------|-------|
| `B.m` (hardware) | `HardwareDetector` | Always returns MyAir5 |
| `B.a` (crypto) | `CryptoHelper` | `javax.crypto.Cipher`, hardcoded same key |
| `B.f` (atomic CAS) | Standard `AtomicReference` | Drop synthetic helper |
| `C.a` (queue) | `ArrayDeque<CanMessageEntry>` | Kotlin stdlib |
| `D.a` (error logging) | `Log` + optional handler | No Crashlytics |
| `E.b` (analytics) | **Removed** | Firebase analytics dropped |
| `x.*` (LocalBroadcast) | Direct calls / SharedFlow | LocalBroadcastManager is deprecated |
| `y.b` (prefs) | `SharedPreferences` / DataStore | Direct Android API |

## UART Protocol Frame Format

- Frames wrapped in `<U>tag</U=crc>` (value from `parse_block_tag` string resource)
- CRC-8 with custom 256-entry lookup table (from `b.java`)
- 63-byte max write chunks with 1ms sleep between chunks
- Config packet on connect: `[0x00, 0xE1, 0x00, 0x00, 0x08, 0x01, 0x00, 0x00]`
- Markers: `<ack>0</ack>` (nack), `<ack>1</ack>` (ack), `<request>Unknown</request>`, `getCAN`

## Poll Cycle (MyAir5)

1. `getSystemData` — inject `type=17`, `AppStore=MyAir5`, strip dhcp/gateway, set `MyAppRev=14.150`
2. `getClock`
3. `getZoneData?zone=1` through `zone=10`
4. No schedule polling for MyAir5 (unlike older systems)

## State Machine

- Sealed class `UartState` with transitions: Connecting → ConfigSent → Polling ↔ AwaitingResponse
- Per-request retry: up to 3 times, then skip
- CAN message queue: up to 25 CAN IDs with retry on failed ack
- "CAN2 in use" response sets CAN busy flag
- `Mutex` replaces `synchronized` blocks

## Inter-App Broadcasts

- Same broadcast actions as original: `com.air.advantage.MESSAGE_FROM_CB`, `com.air.advantage.CAN_TO_CB`, etc.
- Dual-path: secure (signature permission `com.air.android.secure_comms`) + no-permission (AES encrypted)
- Same permission variant for Fujitsu (`fgassist` package detection)
