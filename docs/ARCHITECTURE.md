# Architecture — Pocket AI

## Modules

| Module | Role |
|--------|------|
| `app` | Android UI, Room DB, downloads, prompt profiles, tool execution |
| `llama-bro-sdk` | JNI bridge (llama.cpp + mtmd), chat session pipeline, streaming tag lexer, tool-call parsing |

## Chat flow

1. **ChatViewModel** — user input, optional photo bytes (vision models only)
2. **ChatEngine** — system prompt (`PromptProfile` + memory + language), message augmentation
3. **LlmRuntime** — loads GGUF (+ mmproj for vision), creates `LlamaChatSession` with native tools when `capabilities.json` allows
4. **llama-bro-sdk** — streams tokens, parses tool/thinking tags, in-process tool loop; image turns via `mtmd`
5. **NativeToolExecutor** — runs `web_search` via DuckDuckGo
6. Response normalized and stored in Room

## Model capabilities

`app/src/main/assets/capabilities.json` mirrors `scripts/prompt-benchmark/capabilities.json` (CI enforces sync).

- **native_tools** — SDK `XmlToolFormats` + `NativeToolDefinitions`
- **vision_native** — GGUF + mmproj loaded through llama.cpp `mtmd` (pixels, not label inject)

## Prompt tuning

Per-model prompts in `PromptProfile.kt`. Benchmarks in `scripts/prompt-benchmark/` mirror production prompts.

## Native stack

- Built from `llama-bro-sdk/src/main/cpp` (CMake + NDK): `libllama_bro.so`, `libllama.so`, `libmtmd.so`, `libggml*.so`
- arm64 inference; x86_64 debug builds can run UI only
- Standard models: rolling context window. Tool-capable models: clear-history decode layout.

## Testing

See [docs/TESTING.md](TESTING.md) for the full layered strategy.

- `./gradlew test` — JVM + Robolectric unit tests; feeds the Codecov badge.
- `./gradlew connectedAndroidTest` — emulator tests for Compose UI and native glue.
- On-device benchmark — Settings → includes vision `count15` fixture with real pixels.
- Python benchmarks — against local GGUF weights (maintainer / optional CI smoke).
