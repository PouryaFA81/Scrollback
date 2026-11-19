package app.scrollback

import app.scrollback.update.AppVersion
import app.scrollback.update.GitHubReleases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private fun v(s: String) = AppVersion.parse(s)!!

    @Test fun versionsCompareNumerically() {
        assertTrue(v("v0.10.0") > v("0.9.3"))
        assertTrue(v("0.6.0") > v("0.5.0-debug"))
        assertEquals(0, v("v0.6.0").compareTo(v("0.6.0-debug")))
        assertTrue(v("1.0.0") > v("1.0.0-beta.1"))
        assertTrue(v("0.6.1") > v("0.6"))
        assertNull(AppVersion.parse("nightly"))
    }

    private val json = """
        {"tag_name":"v0.7.0","name":"Scrollback 0.7.0","body":"## Changes\n- SFTP","html_url":"https://github.com/x/y/releases/tag/v0.7.0",
         "draft":false,"prerelease":false,"assets":[
          {"name":"scrollback-0.7.0-debug.apk","url":"https://api.github.com/a/1","browser_download_url":"https://github.com/d/1","size":80000000},
          {"name":"scrollback-0.7.0-universal-release.apk","url":"https://api.github.com/a/2","browser_download_url":"https://github.com/d/2","size":12000000},
          {"name":"scrollback-0.7.0-arm64-v8a-release.apk","url":"https://api.github.com/a/4","browser_download_url":"https://github.com/d/4","size":9000000},
          {"name":"scrollback-0.7.0-armeabi-v7a-release.apk","url":"https://api.github.com/a/5","browser_download_url":"https://github.com/d/5","size":9000000},
          {"name":"SHA256SUMS","url":"https://api.github.com/a/3","browser_download_url":"https://github.com/d/3","size":200}]}
    """.trimIndent()

    @Test fun parsesReleaseAndPicksMatchingApk() {
        val r = GitHubReleases.parse(json)
        assertEquals("v0.7.0", r.tag)
        assertEquals(5, r.assets.size)
        assertEquals("https://api.github.com/a/2", GitHubReleases.pickApk(r, debugBuild = false)!!.apiUrl)
        assertEquals("https://api.github.com/a/4", GitHubReleases.pickApk(r, false, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))!!.apiUrl)
        assertEquals("https://api.github.com/a/5", GitHubReleases.pickApk(r, false, listOf("armeabi-v7a", "armeabi"))!!.apiUrl)
        assertEquals("https://api.github.com/a/2", GitHubReleases.pickApk(r, false, listOf("x86_64"))!!.apiUrl)
        assertEquals("scrollback-0.7.0-debug.apk", GitHubReleases.pickApk(r, debugBuild = true)!!.name)
        assertTrue(GitHubReleases.isNewer(r, "0.6.0"))
        assertFalse(GitHubReleases.isNewer(r, "0.7.0-debug"))
        assertFalse(GitHubReleases.isNewer(r, "0.8.0"))
    }

    @Test fun noDebugApkMeansNoDebugUpdate() {
        val r = GitHubReleases.parse(json.replace("scrollback-0.7.0-debug.apk", "notes.txt"))
        assertNull(GitHubReleases.pickApk(r, debugBuild = true))
    }
}
