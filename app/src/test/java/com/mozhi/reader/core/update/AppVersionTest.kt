package com.mozhi.reader.core.update

import com.mozhi.reader.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun comparesReleaseAndPrereleaseVersions() {
        assertTrue(compareVersionNames("v0.10.0-beta5", "0.10.0-beta4") > 0)
        assertTrue(compareVersionNames("0.10.0", "0.10.0-beta9") > 0)
        assertTrue(compareVersionNames("0.11.0-alpha1", "0.10.9") > 0)
        assertTrue(compareVersionNames("1.0.0-rc.2", "1.0.0-rc.10") < 0)
    }

    @Test
    fun personalBuildUsesOwnedUpdateChannel() {
        assertEquals("1.0.0", BuildConfig.VERSION_NAME)
        assertEquals(10_000, BuildConfig.VERSION_CODE)
        assertEquals(
            "https://api.github.com/repos/xuebing0229/MoRead/releases?per_page=10",
            BuildConfig.UPDATE_RELEASES_API
        )
        assertEquals("https://github.com/xuebing0229/MoRead", BuildConfig.SOURCE_REPOSITORY_URL)
    }
}
