# Testing Guide

This document outlines the testing philosophy, common patterns, gotchas, and execution commands for the AA Service project.

## Testing Philosophy

1. **JVM-First (Robolectric)**: Run all unit and integration tests on the local JVM using Robolectric. Avoid instrumented `androidTest` runs unless testing physical hardware integrations. This keeps tests fast, reliable, and easily integrated with CI/CD.
2. **Behavior & State Verification over Mocks**: Do not mock or spy on what you don't own. Avoid Mockito `spy()` on Android framework components (such as `Activity` or `Service`). Instead, use Robolectric to drive real components and assert on their view hierarchies, lifecycle state (e.g. `isFinishing`), or system shadow records.
3. **Mock Only External Boundaries**: Mock actual physical boundaries, such as the UART `ParcelFileDescriptor` streams, or complex platform managers where Robolectric shadows are insufficient.
4. **No Logic Duplication**: Test the actual side-effects and behaviors of the production code. Never duplicate implementation details or mock private constants via reflection just to assert they match.

---

## Commands

### Run the entire JVM test suite
```bash
./gradlew testDebugUnitTest
```

### Run a specific test class
```bash
./gradlew testDebugUnitTest --tests "com.air.advantage.aaservice.ui.main.MainActivityTest"
```

### Run a specific test case
```bash
./gradlew testDebugUnitTest --tests "com.air.advantage.aaservice.ui.main.MainActivityTest.click disable_device_admin triggers dialog"
```

---

## Common Patterns

### 1. Activity Lifecycle & View Assertions (Robolectric)
Use `Robolectric.buildActivity` to manage the lifecycle, and inspect the real view hierarchy to verify the correct layout is loaded.

```kotlin
@Test
fun `onCreate sets correct layout`() {
    val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
    val activity = controller.get()
    
    // Assert on real UI elements rather than verifying setContentView() via Mockito
    assertNotNull(activity.findViewById(R.id.enable_device_admin))
}
```

### 2. Service Lifecycle & Foreground Status
Verify foreground services using Robolectric's application and service shadows.

```kotlin
@Test
fun `service starts foreground on creation`() {
    val controller = Robolectric.buildService(RebootNotificationService::class.java).create()
    val service = controller.get()
    val shadowService = shadowOf(service)
    
    assertEquals(1234, shadowService.lastForegroundNotificationId)
    assertNotNull(shadowService.lastForegroundNotification)
}
```

### 3. Verification of System Alarms
Verify scheduled system alarms by querying Robolectric's `ShadowAlarmManager`.

```kotlin
@Test
fun `scheduling alarm registers expected alarm`() {
    ServiceHelper.scheduleServiceStart(context, "my.action", 5000)
    
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val shadowAlarm = shadowOf(alarmManager)
    val alarm = shadowAlarm.nextScheduledAlarm
    
    assertNotNull(alarm)
    assertEquals(AlarmManager.ELAPSED_REALTIME, alarm!!.type)
    assertEquals("my.action", shadowOf(alarm.operation).savedIntent.action)
}
```

---

## Gotchas & Best Practices

### 1. Asynchronous Main Looper Dispatch
Some system operations (like dismissing/clicking dialog buttons or removing device admins) execute asynchronously in Android. In Robolectric, you must explicitly idle the main looper to process these events before performing assertions.

```kotlin
dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()

// CRITICAL: Idle the looper to execute asynchronous device policy changes
ShadowLooper.idleMainLooper()

assertFalse(activity.devicePolicyManager.isAdminActive(componentName))
```

### 2. Local Broadcast Delivery
Local broadcasts sent via `LocalBroadcastManager` are posted to the main looper by default. To make them execute immediately and synchronously in tests, use `sendBroadcastSync` instead of `sendBroadcast`.

```kotlin
// Synchronous delivery ensures the receiver runs before asserting side-effects
LocalBroadcastManager.getInstance(activity).sendBroadcastSync(Intent("com.air.advantage.HIDE_WARNING"))
assertTrue(activity.isFinishing)
```

### 3. Explicit Service Intents
Modern Android versions (5.0+) and Robolectric enforce the use of explicit intents when starting or stopping services. Always specify the target service class.

```kotlin
// INCORRECT (will throw IllegalArgumentException)
val intent = Intent().setAction("com.my.action")
context.startService(intent)

// CORRECT
val intent = Intent(context, UartForegroundService::class.java).setAction("com.my.action")
context.startService(intent)
```

### 4. Android SDK Platform Types (Nullable Returns)
Many older Android SDK methods (e.g. `UsbManager.getAccessoryList()`) return platform types that do not have nullability annotations. Kotlin allows you to call operations on these directly, but they can return `null` at runtime. Always use the safe call operator (`?.`) on platform arrays to prevent `NullPointerException`s when no hardware is connected.

```kotlin
val accessories = usbManager.accessoryList
return accessories?.firstOrNull() // Safe call avoids NPE in production
```
