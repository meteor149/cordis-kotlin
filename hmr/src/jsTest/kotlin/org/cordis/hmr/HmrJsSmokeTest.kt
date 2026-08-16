package org.cordis.hmr

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HmrJsSmokeTest {
    @Test
    fun watchFiltersUsePortableGlobRules() {
        val roots = listOf("src/**")
        val ignored = listOf("**/node_modules", "**/.*")
        assertTrue(matchesWatchPath("src/plugins/demo.kt", roots, ignored))
        assertFalse(matchesWatchPath("src/.cache/demo.kt", roots, ignored))
        assertFalse(matchesWatchPath("src/node_modules/pkg/index.js", roots, ignored))
        assertFalse(matchesWatchPath("test/demo.kt", roots, ignored))
    }
}
