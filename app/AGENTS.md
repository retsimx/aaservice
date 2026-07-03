<!-- Parent: ../AGENTS.md -->

# App Module (`:app`)

> Single Android application module — the entire deliverable.

## Constraints

- **No Android framework imports** in `com.air.advantage.aaservice.domain.*` — this is enforced by convention.
- **package name:** `com.air.advantage.aaservice` — all source code lives under this root.
- **minSdk 19** — no Java 8+ APIs without desugaring; use `androidx.core` backports.
- **Hilt only** — manual DI is not allowed; all injection through `@HiltAndroidApp`, `@AndroidEntryPoint`, `@HiltViewModel`.

## Working Here

- Add new classes under the appropriate existing package (`data/`, `domain/`, `service/`, `ui/`, etc.).
- Each new feature should:
  1. Update the work plan in `docs/plans/work/`
  2. Implement domain types first (pure Kotlin)
  3. Add data layer implementation next
  4. Wire via Hilt modules in `di/`
  5. Expose through Service layer
  6. Add UI in Activities/ViewModels last

## Dependencies

- **Depends on:** Nothing (single module)
- **Depended on by:** Nothing
