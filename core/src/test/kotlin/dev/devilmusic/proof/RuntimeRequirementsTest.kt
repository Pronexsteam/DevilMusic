package dev.devilmusic.proof

import kotlin.test.*

class RuntimeRequirementsTest {
    @Test fun unsupportedProviderCannotBeMistakenForMissingPolyfills() {
        assertEquals(RuntimeRequirements.Stage.UNSUPPORTED_ENGINE, RuntimeRequirements.assess(false, false, emptyMap()).stage)
    }
    @Test fun missingEngineFeaturesCannotBePatchedWithJavaScript() {
        assertEquals(RuntimeRequirements.Stage.MISSING_ENGINE_FEATURES, RuntimeRequirements.assess(true, false, RuntimeRequirements.requiredApis.associateWith { true }).stage)
    }
    @Test fun bareEcmaScriptNeedsAnExplicitWebPlatform() {
        val result = RuntimeRequirements.assess(true, true, emptyMap())
        assertEquals(RuntimeRequirements.requiredApis, result.missing)
        assertEquals(RuntimeRequirements.Stage.NEEDS_WEB_PLATFORM_ADAPTER, result.stage)
    }
    @Test fun partialFetchImplementationIsNotEnough() {
        val result = RuntimeRequirements.assess(true, true, mapOf("fetch" to true, "URL" to true))
        assertTrue("ReadableStream" in result.missing)
        assertTrue("crypto.getRandomValues" in result.missing)
        assertFalse(result.playbackProven)
    }
    @Test fun allGlobalsStillDoNotProveAnAndroidSourceOrPlayback() {
        val result = RuntimeRequirements.assess(true, true, RuntimeRequirements.requiredApis.associateWith { true })
        assertEquals(RuntimeRequirements.Stage.API_PRESENCE_ONLY, result.stage)
        assertFalse(result.playbackProven)
    }
}
