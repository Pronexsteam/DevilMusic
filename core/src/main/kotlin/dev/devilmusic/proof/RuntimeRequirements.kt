package dev.devilmusic.proof

/** An API-presence inventory is not proof of standards compliance or working YouTube playback. */
object RuntimeRequirements {
    val requiredApis = setOf("fetch", "Request", "Response", "Headers", "URL", "URLSearchParams",
        "ReadableStream", "TextEncoder", "TextDecoder", "EventTarget", "CustomEvent",
        "AbortController", "FormData", "File", "setTimeout", "clearTimeout", "atob", "btoa",
        "crypto.getRandomValues", "crypto.subtle.digest")
    enum class Stage { UNSUPPORTED_ENGINE, MISSING_ENGINE_FEATURES, NEEDS_WEB_PLATFORM_ADAPTER, API_PRESENCE_ONLY }
    data class Inventory(val stage: Stage, val missing: Set<String>, val playbackProven: Boolean = false)
    fun assess(supported: Boolean, features: Boolean, apis: Map<String, Boolean>): Inventory = when {
        !supported -> Inventory(Stage.UNSUPPORTED_ENGINE, emptySet())
        !features -> Inventory(Stage.MISSING_ENGINE_FEATURES, emptySet())
        else -> (requiredApis - apis.filterValues { it }.keys).let {
            Inventory(if (it.isEmpty()) Stage.API_PRESENCE_ONLY else Stage.NEEDS_WEB_PLATFORM_ADAPTER, it)
        }
    }
}
