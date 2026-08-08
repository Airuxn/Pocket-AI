# Testing strategy

Pocket AI is an Android app with a native llama.cpp stack. A single test
layer cannot cover everything honestly, so we split quality into three
tiers that match where each piece of code actually runs.

---

## 1. Unit tests (JVM + Robolectric)

Run on every push and PR:

```bash
./gradlew test
```

These are the tests that feed the Codecov badge. They cover pure Kotlin
logic and Android components that Robolectric can host (Application, Context,
Room, DataStore, `ShadowActivityManager`, etc.).

Key areas:

| Module | Test focus | Sample files |
|--------|------------|--------------|
| Prompts | `PromptProfile`, `SystemPromptBuilder`, `CodeContinuePrompt`, `UserMessageAugmenter` | `llm/*Test.kt` |
| Validation | `GgufValidator`, `ImagePixelCodec`, `ModelCapabilities` | `llm/*Test.kt`, `data/catalog/*Test.kt` |
| Repositories | `ChatRepository`, `MemoryRepository`, `SettingsRepository`, `OnboardingRepository`, `ModelRepository` | `data/repo/*Test.kt` |
| Diagnostics | `CrashReporter`, `DeviceSelfCheck`, `DeviceRam` | `diagnostics/*Test.kt` |
| Migrations | Every `AppDatabase` schema upgrade | `data/db/AppDatabaseMigrationsTest.kt` |

Robolectric is configured with `isIncludeNoLocationClasses = true` so the
JaCoCo report includes these classes. That is why the badge can reach ~85%
without an emulator.

### Why the badge does not claim 100% of the APK

Codecov intentionally ignores:

- `ui/**` — Compose UI needs `ComposeTestRule` on an emulator.
- `MainActivity.kt`, `PocketAiApp.kt`, `PasswordGate.kt` — lifecycle/entry-point code.
- `LlmRuntime.kt`, `ChatEngine.kt`, `NativeToolExecutor.kt`, `WebSearchClient.kt` — they bridge to the JNI/native layer and real network.
- `OnDeviceBenchmark.kt` — only meaningful on hardware.
- `AppContainer.kt` — pure DI wiring.

The `codecov.yml` lists these exclusions explicitly so the number is honest.

---

## 2. Instrumented tests (Android emulator or device)

Run locally or on a CI runner with a connected emulator:

```bash
./gradlew connectedAndroidTest
```

Current coverage:

- `SmokeInstrumentedTest.kt` — verifies the app package and that the
  instrumented runner can start the app.

Planned additions:

- Compose UI tests for the chat screen, model picker, and settings.
- A small end-to-end test that downloads a tiny GGUF fixture and asserts the
  download completes without crashing the UI.

These tests will not run on every push in the current CI matrix because they
need a slow emulator boot. They are intended for pre-release validation and
for a dedicated Android CI job once a self-hosted or GitHub-hosted ARM runner
is available.

---

## 3. Manual and benchmark tests

Some behavior can only be judged with real model weights and real hardware.

### Prompt benchmark suite

```bash
python3 scripts/prompt-benchmark/run_all.py --skip-download
# (without --skip-download it needs local GGUF weights)
```

Validates that the XML tool-call format works against actual quantized models
before a release enables `native_tools` for a new catalog entry. This is the
reason only Qwen3 1.7B and Llama 3.2 3B currently expose `web_search`.

### On-device benchmark

Inside the app: **Settings → On-device benchmark**. This runs vision and text
fixtures through the native stack and reports tokens/second. It is the final
gate for whether a given phone can run a model comfortably.

### Release smoke checklist

Before a GitHub Release is published:

1. Install the release APK on a clean arm64 device.
2. Download the smallest model (Llama 3.2 1B).
3. Send a chat message and verify streaming text appears.
4. Attach a photo on a vision model and verify the model responds.
5. Ask a current-events question on Qwen3 1.7B or Llama 3.2 3B and verify a
   `web_search` tool call fires and returns results.
6. Force-stop the app and reopen it — chat history and settings must persist.

---

## CI gates today

| Gate | Runs on | Purpose |
|------|---------|---------|
| `./gradlew test` | every push/PR | Unit tests + JaCoCo coverage upload |
| `lint` | every push/PR | Static analysis for the Kotlin and Android code |
| `assembleDebug` | every push/PR | The app still compiles |
| CodeQL | every push/PR | Security analysis for Kotlin/Java/Actions |
| Dependabot | weekly | Dependency updates and vulnerability alerts |
| Release workflow | manual | Signed APK + GitHub Release |

The emulator/hardware test layer is documented here as the next step. The
unit-test layer already caught and fixed real bugs that would have shipped,
so the current setup is not a placeholder — it is the foundation that the
other layers will extend.

---

## Adding a new test

- Pure logic or data classes → JVM test under `app/src/test/java/`.
- Anything that needs `Context`, Room, DataStore, or shadows → Robolectric test
  under `app/src/test/java/`.
- Anything that needs Compose rendering or the native stack → instrumented test
  under `app/src/androidTest/java/` and add it to the release checklist.
- A new model or tool capability → add a benchmark case in
  `scripts/prompt-benchmark/` and sync it to `app/src/main/assets/`.
