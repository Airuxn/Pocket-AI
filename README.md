# Pocket AI

**Airux Pocket AI** on Android — on-device LLM chat for **arm64** phones. Private AI that runs locally via llama.cpp; optional web search uses DuckDuckGo when a model supports native tool calling.

**Package:** `com.localllm.chat` · **Latest:** [v1.0.1](https://github.com/Airuxn/Pocket-AI/releases/latest) · **Project age:** ~1 month (first code July 2026, public release August 2026)

[![CI](https://github.com/Airuxn/Pocket-AI/actions/workflows/ci.yml/badge.svg)](https://github.com/Airuxn/Pocket-AI/actions/workflows/ci.yml)
[![codecov](https://img.shields.io/codecov/c/github/Airuxn/Pocket-AI)](https://codecov.io/gh/Airuxn/Pocket-AI)
[![License](https://img.shields.io/github/license/Airuxn/Pocket-AI)](LICENSE)

**Quality:** CI (unit tests + JaCoCo coverage, lint, debug build) · CodeQL · Dependabot · manual [Release workflow](.github/workflows/release.yml) for APK · Vercel `ignoreCommand` waits for CI + CodeQL if hosted on Vercel

---

## Features

- **Offline chat** with on-device models (llama.cpp JNI)
- **9-model catalog** — Qwen3, Llama 3.2, Gemma, Dolphin (uncensored), SmolVLM vision
- **Per-model personalities** — tuned prompts for identity, coding, and uncensored behavior
- **Native web search** — Qwen3 1.7B and Llama 3.2 3B (XML tool format, benchmark-validated)
- **Photo attach** — native VLM (GGUF + mmproj) on vision models only
- **Memory & coding mode** — per-chat context, continue-code flow for long generations
- **No accounts** — Room DB and models stay on device

---

## Model catalog

Models download in-app from Hugging Face. Tier picks the best fit for your phone's RAM.

| Model | Category | Size | Min RAM | Native tools |
|-------|----------|------|---------|--------------|
| Llama 3.2 1B | Standard | ~810 MB | 4 GB | `web_search` |
| Qwen3 1.7B | Standard | ~1.2 GB | 6 GB | `web_search` |
| Llama 3.2 3B | Standard | ~1.9 GB | 8 GB | `web_search` |
| SmolVLM2 500M Video | Vision | ~545 MB total | 4 GB | — |
| SmolVLM2 2.2B | Vision | ~1.6 GB total | 6 GB | — |
| Gemma 3 4B Vision | Vision | ~3.3 GB total | 8 GB | — |
| Dolphin 3.0 1B | Uncensored | ~810 MB | 4 GB | — |
| Dolphin 3.0 1.5B | Uncensored | ~940 MB | 6 GB | — |
| Dolphin 3.0 3B | Uncensored | ~1.9 GB | 8 GB | — |

Catalog source: [`models.json`](models.json). Capabilities and tool wiring: [`app/src/main/assets/capabilities.json`](app/src/main/assets/capabilities.json).

---

## Install

Download the APK from [Releases](https://github.com/Airuxn/Pocket-AI/releases/latest) (arm64, Android 8+).

```bash
gh release download v1.0.0 --repo Airuxn/Pocket-AI -p app-release.apk --clobber
```

Open the APK on your device, allow install from unknown sources if prompted, then pick and download a model inside the app.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Compose UI (Chat, Models, Settings, Memory)                    │
├─────────────────────────────────────────────────────────────────┤
│  ViewModel → ChatEngine → message augmentation + memory         │
├─────────────────────────────────────────────────────────────────┤
│  LlmRuntime → loads GGUF/mmproj, picks ModelProfile, wires tools  │
├─────────────────────────────────────────────────────────────────┤
│  llama-bro-sdk (JNI) → LlamaChatSession, streaming lexer, tool  │
│  loop, and vision (mtmd) pixels                                   │
├─────────────────────────────────────────────────────────────────┤
│  PromptProfile · ModelCapabilities · NativeToolExecutor ·       │
│  WebSearchClient · Room · DataStore                               │
└─────────────────────────────────────────────────────────────────┘
```

| Layer | Location | Why it matters |
|-------|----------|----------------|
| App (Kotlin + Compose) | `app/src/main/java/` | UI, state management, persistence, downloads |
| llama.cpp JNI SDK | `llama-bro-sdk/` | NDK bridge to quantized inference and vision |
| Model + tool capabilities | `app/src/main/assets/capabilities.json` | Runtime feature flags per model, synced to benchmarks |
| Downloadable model catalog | `models.json` | Hugging Face source URLs and RAM tiering |
| Prompt benchmarks | `scripts/prompt-benchmark/` | Pre-release XML tool-call scoring |

The Kotlin layer owns everything the user can touch: chat orchestration, model downloads, settings, memory, and crash diagnostics. The SDK layer owns the hard parts: native session lifecycle, streaming token lexing, XML tool-call parsing, and the ReAct-style loop. The boundary is intentionally thin so that most new features are Kotlin-only.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full chat pipeline, prompt tuning, and native stack.

**Deep dive:** [How on-device tool calling works](docs/ON_DEVICE_TOOL_CALLING.md) — XML format, SDK tool loop, DuckDuckGo integration, and benchmark gating.

---

## Native tools (per model)

| Model | Native tools | Photo inject |
|-------|-------------|--------------|
| Qwen3 1.7B | `web_search` | ✅ |
| Llama 3.2 3B | `web_search` | ✅ |
| Gemma, Dolphin, SmolVLM | — | ✅ |

Tools are enabled automatically from `capabilities.json` via `XmlToolFormats` + DuckDuckGo. CI keeps the app copy in sync with the benchmark suite.

---

## Build & test

```bash
./gradlew :app:assembleDebug
./gradlew test
./gradlew connectedAndroidTest          # needs an emulator/device; see docs/TESTING.md
python3 scripts/prompt-benchmark/run_all.py --skip-download   # needs local GGUF weights
```

Release (maintainers):

```bash
export LOCALCHAT_KEYSTORE_PASS='…'   # or POCKETAI_KEYSTORE_PASS
bash scripts/gradle-release.sh    # → dist/app-release.apk
bash scripts/release.sh           # local: build + GitHub Release

# Or: GitHub → Actions → Release → Run workflow (LOCALCHAT_KEYSTORE_PASS secret)
```

Requirements: JDK 17+, Android SDK platform **36**, build-tools **35.0.0**.

**Coverage strategy:** Unit tests (Robolectric + JVM) cover the core Kotlin logic. The reported ~85% badge intentionally excludes Compose UI, `MainActivity`, the JNI bridge, and the on-device benchmark — those need emulator or hardware tests. See [docs/TESTING.md](docs/TESTING.md) for the full layered approach.

---

## What makes this different

Most on-device Android LLM demos are thin wrappers around a single hardcoded model. Pocket AI treats the phone as a real platform:

- **Model catalog with runtime capability detection** — the app does not assume every GGUF behaves the same; it loads per-model prompts, tool eligibility, and RAM tiers from JSON and switches behavior at runtime.
- **Native tool calling without a backend** — search only fires when the model itself emits XML, and only for models that passed the offline benchmark. No API keys, no cloud orchestrator, no accounts.
- **Vision through real llama.cpp mtmd** — not a cloud vision API; photos are encoded as native llama.cpp image tokens on the same session as the text.
- **Privacy by design** — chat history and weights live in app-private storage; the only network call is DuckDuckGo when a tool-capable model triggers it.

---

## Demo

Download the [latest APK](https://github.com/Airuxn/Pocket-AI/releases/latest), pick a model, and start a chat. Screenshots and a short screen recording are attached to the release notes.

---

## Lessons learned

1. **Small quantized models are unreliable at JSON tool calls.** We moved to a strict XML template baked into the system prompt, with a streaming lexer that only emits a tool call after the closing tag arrives. This dramatically reduced false positives and premature network calls.
2. **Robolectric tests catch real bugs if you instrument them honestly.** The reported ~85% coverage surfaced two production bugs: `CrashReporter` truncated reports to zero bytes because `fsync` reopened the file, and Dolphin models were misidentified as Phi because the substring check was ordered wrong. Both fixes are in the same commit that added the tests.
3. **Coverage badges must be honest.** Hiding UI and JNI code from the badge is not cheating; it is documenting which quality gates live where. The missing layers are emulator tests, hardware smoke tests, and prompt benchmarks — and we say so explicitly.
4. **A one-person project can still have production discipline.** Signed commits, branch protection on the other public repos, CI, CodeQL, Dependabot, and release workflows all fit in a small repo without becoming bureaucracy.

---

## Repository layout

| Path | Description |
|------|-------------|
| `app/` | Kotlin + Compose application |
| `llama-bro-sdk/` | On-device llama.cpp JNI wrapper |
| `models.json` | Downloadable model catalog |
| `scripts/prompt-benchmark/` | Offline prompt + tool + inject benchmarks |
| `docs/` | Architecture, testing strategy, and maintainer notes |
| `docs/ARCHITECTURE.md` | Module layout and CI/release flow |
| `docs/TESTING.md` | Unit, instrumented, and manual test layers |
| `CHANGELOG.md` | Release history |

---

## Stack

Kotlin · Jetpack Compose · Room · llama.cpp JNI (+ mtmd) · minSdk 26 · targetSdk 36

---

## Security

No backend, no cloud inference, no secrets in git. Web search runs only when a tool-capable model triggers it. Chat history and GGUF weights stay in app-private storage.

See [SECURITY.md](SECURITY.md) for data handling, web search, and reporting.

---

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature-name`
3. Commit changes: `git commit -am 'Add feature'`
4. Push to branch: `git push origin feature-name`
5. Submit a Pull Request

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

Downloaded GGUF models remain subject to their upstream licenses (see each model card on Hugging Face). Optional **web search** sends queries to DuckDuckGo — use only for data you are allowed to access.

---

## 🙏 Acknowledgments

- [llama.cpp](https://github.com/ggerganov/llama.cpp) — on-device LLM inference
- [Jetpack Compose](https://developer.android.com/jetpack/compose) and [Room](https://developer.android.com/training/data-storage/room) — Android UI and local storage
- [Hugging Face](https://huggingface.co/) — GGUF model hosting
- [DuckDuckGo](https://duckduckgo.com/) — optional web search
- [llama.cpp](https://github.com/ggerganov/llama.cpp) — on-device LLM + mtmd vision

---

## 📞 Support

For support and questions:

- Create an issue on [GitHub](https://github.com/Airuxn/Pocket-AI/issues)
- Security: see [SECURITY.md](SECURITY.md)
- Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

---

**⭐ If this project helped you, please give it a star!**
