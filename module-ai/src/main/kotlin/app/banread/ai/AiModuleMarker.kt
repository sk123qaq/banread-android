package app.banread.ai

import app.banread.model.AiRuntime

/**
 * Placeholder entry point for `:module-ai` (Slice 4 implementation).
 * Provider adapters, SSE parsers, and Tink keystore wiring stay `internal`.
 */
object AiModuleMarker {
    val contractClass = AiRuntime::class
}
