package app.scrollback.update

import org.json.JSONObject

/** "v0.6.0", "0.6.0-debug", "0.10.1-beta.2" → comparable version. Pure logic, unit-tested. */
data class AppVersion(val parts: List<Int>, val pre: String?) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            pre == other.pre -> 0
            pre == null -> 1 // a final release beats its pre-releases
            other.pre == null -> -1
            else -> pre.compareTo(other.pre)
        }
    }

    override fun toString() = parts.joinToString(".") + (pre?.let { "-$it" } ?: "")

    companion object {
        fun parse(raw: String): AppVersion? {
            val t = raw.trim().removePrefix("v").removePrefix("V").substringBefore('+')
                .removeSuffix("-debug").removeSuffix("-release")
            val core = t.substringBefore('-')
            val pre = t.substringAfter('-', "").ifBlank { null }
            val nums = core.split('.').map { it.toIntOrNull() ?: return null }
            return if (nums.isEmpty()) null else AppVersion(nums, pre)
        }
    }
}

data class ReleaseAsset(val name: String, val apiUrl: String, val downloadUrl: String, val size: Long)

data class ReleaseInfo(
    val tag: String,
    val title: String,
    val notes: String,
    val htmlUrl: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
) {
    val version: AppVersion? get() = AppVersion.parse(tag)
}

object GitHubReleases {
    const val REPO = "PouryaFA81/Scrollback"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val PAGE_URL = "https://github.com/$REPO/releases"

    fun parse(json: String): ReleaseInfo {
        val o = JSONObject(json)
        val arr = o.optJSONArray("assets")
        val assets = (0 until (arr?.length() ?: 0)).map { i ->
            val a = arr!!.getJSONObject(i)
            ReleaseAsset(
                name = a.optString("name"),
                apiUrl = a.optString("url"),
                downloadUrl = a.optString("browser_download_url"),
                size = a.optLong("size"),
            )
        }
        return ReleaseInfo(
            tag = o.optString("tag_name"),
            title = o.optString("name").ifBlank { o.optString("tag_name") },
            notes = o.optString("body"),
            htmlUrl = o.optString("html_url").ifBlank { PAGE_URL },
            draft = o.optBoolean("draft"),
            prerelease = o.optBoolean("prerelease"),
            assets = assets,
        )
    }

    /**
     * Release builds update from `scrollback-<ver>-<abi>-release.apk` (first match in the device's
     * [abis] order, then `-universal-release.apk`); debug builds from `*-debug.apk`. Debug and
     * release are signed with different keys and have different application ids, so never mixed.
     */
    fun pickApk(release: ReleaseInfo, debugBuild: Boolean, abis: List<String> = emptyList()): ReleaseAsset? {
        val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (debugBuild) return apks.firstOrNull { it.name.endsWith("-universal-debug.apk") } ?: apks.firstOrNull { it.name.endsWith("-debug.apk") }
        val rel = apks.filter { it.name.endsWith("-release.apk") }
        return abis.firstNotNullOfOrNull { abi -> rel.firstOrNull { it.name.endsWith("-$abi-release.apk") } }
            ?: rel.firstOrNull { it.name.endsWith("-universal-release.apk") }
            ?: rel.firstOrNull()
            ?: apks.firstOrNull { !it.name.contains("debug") }
    }

    fun isNewer(release: ReleaseInfo, current: String): Boolean {
        val r = release.version ?: return false
        val c = AppVersion.parse(current) ?: return true
        return r > c
    }
}
