# BanRead Android — AI Companion Reader

Android companion reader client for `book-corpus`, structured as a 7-module deep-module Gradle workspace (`:core-model` + 6 Android domain modules + `:app`).

## Module Dependency Topology

```text
:app (Compose UI + ViewModels + Nav)
 ├──> :module-library   (LibraryVault)
 ├──> :module-reader    (ReaderEngine)
 ├──> :module-companion (CompanionEngine)
 ├──> :module-viz       (VizHost)
 ├──> :module-sync      (SyncCoordinator)
 └──> :module-ai        (AiRuntime)

Cross-module edges (public interfaces + :core-model only):
  :module-reader    ──> :module-library
  :module-companion ──> :module-library, :module-ai, :module-sync
  :module-sync      ──> :module-library, :module-reader

All modules ──> :core-model (Pure JVM Kotlin domain models, interfaces, seams, ProfileRegistry)
```

## Seam & Visibility Rules

1. **Strict `internal` Encapsulation**: Each `:module-*` exposes only its Deep Module facade interface and uses `internal` for Room DAOs, OkHttp DTOs, `foliate-js` JS bridges, PDFium JNI wrappers, and `BlueprintAuditor` internals. Modules communicate strictly via `:core-model` domain types and public interfaces.
2. **Single-Writer Ownership**:
   - `LibraryVault` owns source ingestion, L1 blocks, `chapter_role`, `FootnoteLink`, and compiled XHTML assets.
   - `ReaderEngine` is the sole writer of local `ReadingCursor`.
   - `SyncCoordinator` merges remote `DeviceProgressShard`s and coordinates monotonic `SpoilerWatermark` advancement via `ReaderEngine.raiseWatermarkFromRemote`.
   - `CompanionEngine` is strictly read-only with respect to reading progress.
3. **Real Seams Only ("Two adapters means a real seam")**:
   - `BookRendererPort` (`FoliateWebAdapter`, `PdfiumPageAdapter`, `FakeRendererAdapter`)
   - `LlmProviderPort` (`OpenAiCompatAdapter`, `GeminiAdapter`, `AnthropicAdapter`, `OllamaAdapter`, `ScriptedLlmFakeAdapter`)
   - `SecretVaultPort` (`TinkKeystoreVaultAdapter`, `InMemorySecretVaultAdapter`)
   - `DesktopBridgePort` (`HttpDesktopGatewayAdapter`, `InMemoryDesktopGatewayAdapter`)
   - `StateSyncPort` (`WebDavShardAdapter`, `S3R2ShardAdapter`, `InMemoryStateSyncAdapter`)
4. **No Local Fallback for Cross-Book Tools**: `compare_context` and `method_audit` require `DesktopBridgePort` (`cross_search = true`) and return `AppFailure.DesktopConnectionRequired` offline without consuming LLM tokens.

## Domain Docs

- [GLOSSARY.md](file:///home/shike/文档/banread-android/GLOSSARY.md)
- [ADR-0001: Unified Reflow via `foliate-js`](file:///home/shike/文档/banread-android/docs/adr/0001-unified-reflow-foliate-js.md)
- [ADR-0002: Dual-Track Local Storage](file:///home/shike/文档/banread-android/docs/adr/0002-dual-track-local-storage.md)
- [ADR-0003: Split Sync Plane](file:///home/shike/文档/banread-android/docs/adr/0003-split-sync-plane.md)
- [ADR-0004: Derived Registry & Viz Reuse](file:///home/shike/文档/banread-android/docs/adr/0004-derived-registry-and-viz-reuse.md)
- [ADR-0005: Two-Tier Scanned PDF](file:///home/shike/文档/banread-android/docs/adr/0005-two-tier-scanned-pdf.md)

## Running Tests

### `:core-model` (Pure JVM, no Android SDK required)

Using the provisioned Eclipse Temurin JDK 21:

```sh
JAVA_HOME=/home/shike/.local/share/jdks/temurin-21 ./gradlew :core-model:test --info
```

### Android Modules (User-Space Android SDK)

`settings.gradle.kts` automatically detects an Android SDK via `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`. Minimal user-space SDK packages (`platform-tools`, `platforms;android-35`, `build-tools;35.0.0`) live at `/home/shike/Android/Sdk` and are pointed to by git-ignored `local.properties`:

```properties
sdk.dir=/home/shike/Android/Sdk
```

Then build or test Android modules:

```sh
JAVA_HOME=/home/shike/.local/share/jdks/temurin-21 ANDROID_HOME=/home/shike/Android/Sdk \
  ./gradlew :core-model:test :module-library:testDebugUnitTest :app:compileDebugKotlin --info
```
