# AA Service

Android app for controlling Advantage Air MyAir5 HVAC systems. Runs on the wall tablet (or any Android device) and talks to the control box (CB) over one of two transports:

- **USB** — FTDI USB-serial bridge via Android USB Accessory mode, speaking the original binary XML frame protocol (the stock-tablet path).
- **WebSocket** — a JSON mailbox stream to [`cb-daemon`](https://github.com/retsimx/cb-daemon), which owns the RS-485/CAN2 register sync to the control box (the replacement-tablet path).

`applicationId` is `com.air.advantage.aaservice2` (version `14.150-rebuild.2`). This is a Kotlin reimplementation of the proprietary AA Service app (v14.150, `com.air.advantage.aaservice`) reconstructed from decompiled source; protocol behavior is preserved exactly.

## Features

- **Dual transport with mode switch** — USB accessory UART or WebSocket mailbox, selectable in-app with a settings card; exclusive (only one active path at a time)
- **Magisk daemon lifecycle** — on WebSocket mode, starts/stops the root-held `cb-daemon` via `su`/`control.sh` (loopback hosts only; remote daemon hosts skip Magisk)
- **USB UART bridge** — Android USB Accessory mode, FTDI accessory filter, chunked writes, ping-driven dispatch engine
- **WebSocket mailbox client** — OkHttp client with ping keepalive (30 s), exponential reconnect backoff, ack tracking (10 s timeout), session snapshot/event consumption
- **Mailbox register mapping** — full CB register bank (zone config, unit activation, zone state/limits, system status, flush, aircon error, sensor pairing) to/from daemon JSON wire shapes
- **CAN message queue** — up to 25 concurrent IDs, thread-safe, retry with ack/nack handling, priority dispatch, content-identical frame dedup
- **Foreground service** — persistent UART lifecycle, device-open guards, crash-count/PFD lifecycle contract, periodic broadcast contract
- **Secure inter-app broadcasts** — signature permission (`com.air.android.secure_comms`) plus AES-CBC encryption path for non-signature receivers
- **State cache & polling** — system data cache, poll queue, raw CAN → typed transform
- **Admin device policy & reboot notification** — device-admin keep-alive, OTA package-replaced reboot flow
- **Alert/intent bridge** — system broadcasts surface as alert dialogs in the UI

## Architecture

Layered with unidirectional dependency: **UI → Service → Domain → Data**.

```
┌──────────────────────────────────────────────────────┐
│  UI Layer                                            │
│  MainActivity (transport settings) · AlertActivity   │
│  UsbConnectActivity (USB accessory attach flow)      │
├──────────────────────────────────────────────────────┤
│  Service Layer                                       │
│  UartForegroundService · ModeSwitchCoordinator       │
│  TransportRouter · TransportStatusStore              │
│  RebootNotificationService · daemon/SuDaemonLifecycle│
├──────────────────────────────────────────────────────┤
│  Domain Layer (pure Kotlin)                          │
│  SystemData · ZoneData · CanMessage · Frame · Poll   │
│  UartDispatchEngine · UartStateMachine · transformers│
├──────────────────────────────────────────────────────┤
│  Data Layer                                          │
│  protocol/ (FrameParser, CrcCalculator)              │
│  uart/ (UsbAccessoryDataSource, MockUartDataSource)  │
│  mailbox/ (OkHttpMailboxWsClient, mappers, DTOS)     │
│  repository/ (DataCache, PollQueue)                  │
└──────────────────────────────────────────────────────┘
```

### Package Structure

```
com.air.advantage.aaservice/
├── data/
│   ├── uart/          — UART transport (UsbAccessoryDataSource, MockUartDataSource)
│   ├── protocol/      — FrameParser, CrcCalculator (pure Kotlin, no Android deps)
│   ├── mailbox/       — WebSocket client, config, wire DTOS, MyAir5 mapper
│   └── repository/    — DataCacheRepository, PollQueueRepository
├── domain/
│   ├── model/         — SystemData, ZoneData, CanMessage, Frame, PollEntry
│   ├── state/         — UartDispatchEngine, UartStateMachine
│   ├── transform/     — GetSystemDataTransformer
│   └── mailbox/       — MailboxRawCanEncoder, MailboxBroadcastMapper
├── service/
│   ├── UartForegroundService — main foreground service (coroutine-based)
│   ├── TransportRouter       — USB vs WS selection
│   ├── ModeSwitchCoordinator — exclusivity + Magisk lifecycle sequencing
│   ├── TransportStatusStore  — observable transport status
│   ├── RebootNotificationService
│   └── daemon/               — DaemonLifecycle, SuDaemonLifecycle, ProcessRunner
├── ui/
│   ├── main/          — MainActivity + MainViewModel (incl. transport card)
│   ├── alert/         — AlertActivity + AlertViewModel
│   └── usb/           — UsbConnectActivity
├── receiver/          — USB permission, data/message, device admin, alert, reboot receivers
├── di/                — Hilt modules (App, Service, Uart, Mailbox) + entry points
├── util/              — CryptoHelper, PreferencesManager, HardwareDetector, ServiceHelper, TransportMode
└── AAServiceApp.kt    — Application class
```

## Tech Stack

| Component | Choice |
|-----------|--------|
| Language | Kotlin 1.9.20 |
| Minimum SDK | 19 (Android 4.4 KitKat) |
| Compile SDK | 34 |
| Target SDK | 30 (Android 11) |
| Build | Gradle 8.5 + Android Gradle Plugin 8.2.0 |
| Async | Kotlin Coroutines 1.7.3 + Flow |
| DI | Dagger Hilt 2.48 |
| UI | XML layouts with ViewBinding |
| USB | Android USB Accessory API |
| WebSocket | OkHttp |
| Crypto | `javax.crypto` AES/CBC/PKCS7Padding |
| Quality | ktlint (warnings-as-errors lint gate), JaCoCo coverage, Robolectric unit tests |

## Prerequisites

- JDK 17 (required — kapt fails on newer JDKs)
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

# Unit tests
./gradlew test

# Lint + format
./gradlew lintDebug ktlintCheck
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Transports

### USB (default)

The stock-tablet path: the app is a USB accessory client that connects to the control box through an FTDI bridge (accessory filter `FTDI / FTDIUARTDemo` and `Android Accessory FT312D`, version omitted to match both stock and live CB filters). The `<U>payload</U=crc>` frame format, CRC-8, poll cycle, and init sequence are the reverse-engineered CB↔tablet protocol documented in [`aa_interop`](https://github.com/gundy/aa_interop) — see the frame-format and register references there.

- **Dispatch engine:** ping-driven, thread-safe CAN queue with ack/nack retry, direct-request and broadcast paths

### WebSocket (cb-daemon)

The replacement-tablet path: the app connects to [`cb-daemon`](https://github.com/retsimx/cb-daemon) over a single-session mailbox stream and exchanges typed register JSON instead of raw frames.

- **Endpoint:** `ws://<host>:2026/v1/mailbox-stream` (default `ws://127.0.0.1:2026/v1/mailbox-stream`; operator-configurable in the UI)
- **Handshake:** daemon pushes a `snapshot`; thereafter `event`, `read_result`, `ack`, `status`, `error`
- **Outbound:** `write` (typed register writes via `MyAir5OutboundMailboxMapper`), `read`, `command`, `resync`/`flush_unit` for register-bank flush
- **Registration:** raw CAN tokens (`07`/`08` addressed writes, `06` flush) parse into addressed raw-hex writes
- **Magisk integration:** loopback hosts start/stop the root daemon via `SuDaemonLifecycle` (`su` + `control.sh`); remote hosts (e.g. a LAN Pi) skip it
- **Cleartext:** `ws://` is required by design (LAN-local HVAC frames, arbitrary daemon host) — enforced in `network_security_config.xml` with the `InsecureBaseConfiguration` lint suppression reviewed in issue #96

## State Machine

```
Connecting → ConfigSent → Polling ↔ AwaitingResponse
```

- `Connecting` — USB accessory attached, initializing UART stream
- `ConfigSent` — init config packet sent, waiting for ack
- `Polling` — Idle state, ready to send next request
- `AwaitingResponse` — Request sent, waiting for response frame

Transitions are managed via a sealed class `UartState` with coroutine-based concurrency.

## Testing

Unit tests (JUnit + Robolectric + Mockito-Kotlin) cover the protocol layer, state machine, dispatch engine, repositories, mailbox client/mappers, receivers, services, and daemon lifecycle. Coverage reported via JaCoCo. Instrumented tests are not required for this device-bound app.

## License

[MIT](LICENSE)

## Related Projects

- [`cb-daemon`](https://github.com/retsimx/cb-daemon) — Rust control-box daemon: CB register sync engine + WebSocket mailbox server this app connects to
- [`aa_interop`](https://github.com/gundy/aa_interop) — upstream reverse-engineered CB↔tablet protocol research the protocol layers are based on
