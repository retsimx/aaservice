# Code Review Standards

## Checklist

Every PR must be checked for:

1. **Protocol correctness** — Does the implementation match the decompiled reference behavior?
2. **Layer isolation** — No Android imports in `domain/`, no business logic in UI/receivers
3. **Coroutine safety** — All UART I/O on `Dispatchers.IO`, UI state on `Dispatchers.Main`
4. **Resource leaks** — FileInputStream/FileOutputStream closed in `finally` or `use {}`
5. **Error handling** — UART disconnects, CRC failures, and timeouts handled gracefully
6. **State machine** — All `UartState` transitions valid; no illegal transitions

## Severity

| Level | Meaning | Action |
|-------|---------|--------|
| Blocker | Breaks protocol compatibility or crashes on device | Must fix before merge |
| Major | Incorrect behavior, resource leak, or thread safety issue | Should fix before merge |
| Minor | Style, naming, or documentation gap | Fix if convenient |
| Nit | Preference, not a defect | Optional |

## Anti-Patterns to Flag

- Using `Thread`/`Handler` instead of coroutines
- Importing Android classes into `domain/` package
- Hardcoding secrets or keys in source
- Ignoring CRC validation on received frames
- Synchronous I/O on main thread
