# AA Service

> Android app for controlling Advantage Air MyAir5 HVAC systems via USB UART.

## Architecture
See [ARCHITECTURE.md](ARCHITECTURE.md) for the full domain map.

## Documentation
- [Design Docs](docs/design-docs/index.md) — architectural decisions and core beliefs
- [Plans](docs/plans/) — design references (`designs/`) and execution plans (`work/`)
- [Product Specs](docs/product-specs/index.md) — feature specifications
- [References](docs/references/) — external library docs for LLMs

## Quality & Planning
- [Quality Score](docs/QUALITY-SCORE.md) — per-domain quality grades
- [Code Review](docs/CODE-REVIEW.md) — review standards and checklist
- [Plans](docs/PLANS.md) — planning conventions
- [Tech Debt](docs/plans/work/tech-debt-tracker.md) — known debt tracker

## Project Structure

```
aaservice/
├── app/                          ← Android application module
│   ├── build.gradle.kts          ← Dependencies, SDK versions, plugins
│   └── src/main/
│       ├── AndroidManifest.xml   ← Activities, services, receivers, permissions
│       ├── res/                  ← Drawables, layouts, mipmaps, values, XML configs
│       └── java/                 ← Source code (being built incrementally)
├── build.gradle.kts              ← Root build file
├── settings.gradle.kts           ← Module declarations
├── gradlew                       ← Gradle wrapper
├── local.properties              ← SDK path (gitignored)
├── reference/                    ← Decompiled original APK sources (gitignored)
└── docs/                         ← Knowledge base
```

## Quick Rules

1. **Android SDK location:** `/opt/android-sdk` on this machine. Set via `local.properties` (`sdk.dir=/opt/android-sdk`) or `ANDROID_SDK_ROOT` env var.
2. **Domain layer is pure Kotlin** — no Android framework imports allowed in `com.air.advantage.aaservice.domain.*`.
3. **Preserve protocol behavior exactly** — the decompiled reference is the source of truth for UART frame format, CRC, and poll cycle.
4. **No Firebase, no analytics** — error logging via `Log` + optional handler only.
5. **All PRs are draft** until CI confirms the build passes.

## Environment

| Variable | Value |
|----------|-------|
| Android SDK root | `/opt/android-sdk` |
| JDK | 17 |
| Gradle | 8.5 (wrapper) |
| Build command | `./gradlew assembleDebug` |

<!-- MANUAL: Notes below this line are preserved on regeneration -->
