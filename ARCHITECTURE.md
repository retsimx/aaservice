# AA Service — Architecture

## Domain Map

The app communicates with Advantage Air MyAir5 HVAC systems over USB UART using a custom binary XML frame protocol. It serves as a bridge between the USB serial port and Android system components (broadcasts, activities, services).

```
  ┌─────────────────────────────────────────────────────────────┐
  │  Physical Layer                                              │
  │  Android USB Accessory → FTDI UART → MyAir5 HVAC Controller  │
  └───────────────────────────┬─────────────────────────────────┘
                              │ raw bytes
                              ▼
  ┌─────────────────────────────────────────────────────────────┐
  │  Data Layer                                                 │
  │  UartDataSource · FrameParser · CrcCalculator               │
  │  MessageReceiver · DataBroadcaster                          │
  │  DataCacheRepository · CanStateRepository                   │
  │  PollQueueRepository · PreferencesManager                   │
  └───────────────────────────┬─────────────────────────────────┘
                              │ domain types
                              ▼
  ┌─────────────────────────────────────────────────────────────┐
  │  Domain Layer                                               │
  │  SystemData · ZoneData · ScheduleData · CanMessage           │
  │  Frame types · UartStateMachine                              │
  │  PollAllDataUseCase · SendCanMessageUseCase                  │
  └───────────────────────────┬─────────────────────────────────┘
                              │ use-case results
                              ▼
  ┌─────────────────────────────────────────────────────────────┐
  │  Service Layer                                              │
  │  UartForegroundService · RebootNotificationService          │
  └───────────────────────────┬─────────────────────────────────┘
                              │ UI state
                              ▼
  ┌─────────────────────────────────────────────────────────────┐
  │  UI Layer                                                   │
  │  MainActivity · AlertActivity · UsbConnectActivity          │
  │  MainViewModel · AlertViewModel                             │
  └─────────────────────────────────────────────────────────────┘
```

Dependency direction: **UI → Service → Domain → Data** (unidirectional).

## Package Layering

```
com.air.advantage.aaservice/
├── AAServiceApp.kt              ← Application class (Hilt entry point)
├── di/                          ← Hilt modules (wires all layers)
├── data/                        ← I/O, persistence, protocol parsing
│   ├── uart/                    — USB serial read/write
│   ├── protocol/                — Frame parse/serialize, CRC-8 (pure Kotlin)
│   ├── broadcast/               — Inter-app message routing
│   ├── repository/              — Data caches, CAN state, poll queue
│   └── prefs/                   — SharedPreferences wrapper
├── domain/                      ← Business logic, no Android imports
│   ├── model/                   — Core types (SystemData, ZoneData, CanMessage)
│   ├── usecase/                 — Orchestration (poll, send, get)
│   └── state/                   — UART state machine (sealed class)
├── service/                     ← Android foreground services
├── ui/                          ← Activities + ViewModels
│   ├── main/
│   ├── alert/
│   └── usb/
├── receiver/                    ← BroadcastReceivers
└── util/                        — HardwareDetector, CryptoHelper, Constants
```

## Key Integration Points

| Integration | Mechanism | Direction |
|-------------|-----------|-----------|
| USB → UART data | `FileInputStream`/`FileOutputStream` on accessory fd | Data → Domain |
| Domain state → Service | Coroutine `Flow` collection | Domain → Service |
| Service → UI | `SharedFlow` + `StateFlow` in ViewModels | Service → UI |
| App → System broadcasts | `sendBroadcast` with signature permission | Service → Android |
| System → App broadcasts | `BroadcastReceiver` registered in manifest | Android → Receiver |
| UI → Service intents | `startForegroundService` with action strings | UI → Service |

## Hardware Constraints

- **Only MyAir5** is supported (hardware detection always returns MyAir5)
- USB Accessory mode only (not USB host)
- FTDI UART at up to 63 bytes/chunk with 1ms inter-chunk delay
- CRC-8 with a 256-entry lookup table for frame integrity

## Protocol Flow

```
[Connect] → [Send Config Packet] → [Poll Loop] ↔ [Await Response]
              0x00E1000008010000       │
                                       ├─ getSystemData (type=17, AppStore=MyAir5)
                                       ├─ getClock
                                       └─ getZoneData?zone=1..10
```

CAN messages queued (max 25) with per-message retry (up to 3), ack/nack tracking, and Mutex-guarded access.
