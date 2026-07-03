# Core Beliefs

- **Source of truth is the decompiled reference.** When in doubt, consult `/home/lewis/Projects/aircon/aaservice/reference/sources/`. Preserve the original protocol behavior exactly.
- **No Firebase, no analytics.** The reconstruction excludes Crashlytics and Firebase Analytics from the original. Error logging uses `Log` + optional handler.
- **Pure Kotlin domain layer.** The `domain/` package must not import any Android framework class. All Android dependencies live in `data/`, `service/`, `ui/`, and `receiver/`.
- **Prefer `javax.crypto` over custom crypto.** AES-128-CBC via built-in Android `Cipher` — no Bouncy Castle or extra libraries.
- **No bundled library code.** Every dependency must come from Gradle (Maven Central / Google Maven). The decompiled APK's embedded libs are replaced with standard AndroidX + Hilt.
