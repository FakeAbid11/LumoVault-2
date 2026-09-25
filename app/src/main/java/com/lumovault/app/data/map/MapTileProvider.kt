package com.lumovault.app.data.map

import android.content.Context
import com.lumovault.app.BuildConfig
import com.lumovault.app.domain.map.TileTemplate
import java.io.File
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex

/**
 * The map's tile provider, and the honest handling of not having one.
 *
 * OSM's own usage policy is explicit that the public tile servers are not a production service for other
 * people's apps: a declared User-Agent is mandatory, bulk fetching is forbidden, and caching is expected. So
 * the host is a build input — `MAP_TILE_URL` as a `{z}/{x}/{y}` template, `MAP_TILE_USER_AGENT`,
 * `MAP_TILE_ATTRIBUTION`, `MAP_TILE_MAX_ZOOM` — the same shape the Telegram credentials take, and for the same
 * reason: an absent value has to mean a clearly reported "not configured", never a placeholder that looks real
 * and never a crash.
 *
 * With no template, [tileSource] is null and the map still draws its markers on a plain canvas — every photo
 * position, the clusters, the strip and the whole viewer hand-off work without a single tile. That is the
 * difference between a build that cannot show tiles and a feature that does not exist, and the screen says
 * which one it is showing. The caller must then turn osmdroid's data connection off: its *own* default source
 * is Mapnik at tile.openstreetmap.org, so leaving the tile source unset would fetch from the public servers
 * rather than from nothing. See MapScreen's factory.
 *
 * osmdroid's own configuration is global and read when the first `MapView` is constructed, which is why
 * [configure] is idempotent and must run before that: setting a user agent afterwards is a value the library
 * has already worked around. Nothing here touches `SharedPreferences` — the three values that matter are plain
 * setters, and the library's own `load(context, preferences)` is the only thing that would have.
 */
object MapTileProvider {
    private const val TILE_ENDING = ".png"

    /** Namespaces the on-disk tile cache; keep it stable, because a rename silently discards every tile. */
    private const val SOURCE_NAME = "lumovault"

    @Volatile
    private var configured = false

    /** The configured template, or null when this build was given no tile host. */
    val template: TileTemplate? = TileTemplate.of(BuildConfig.MAP_TILE_URL)

    val attribution: String = BuildConfig.MAP_TILE_ATTRIBUTION.trim()

    val isConfigured: Boolean get() = template != null

    fun configure(context: Context) {
        if (configured) return
        synchronized(this) {
            if (configured) return
            val configuration = Configuration.getInstance()
            // Not a nicety: osmdroid's tile downloader refuses to fetch when the agent is still its own
            // default, so an unconfigured User-Agent is a blank map rather than a polite one.
            configuration.setUserAgentValue(BuildConfig.MAP_TILE_USER_AGENT.ifBlank { context.packageName })
            val base = File(context.cacheDir, "osmdroid")
            configuration.setOsmdroidBasePath(base)
            // App-private storage, which is why no storage permission is involved: the legacy default was an
            // external path, and this app has no reason to write where other apps can read.
            configuration.setOsmdroidTileCache(File(base, "tiles"))
            configured = true
        }
    }

    /** The source for [template], or null when none is configured. */
    fun tileSource(): XYTileSource? {
        val urlTemplate = template ?: return null
        val maxZoom = BuildConfig.MAP_TILE_MAX_ZOOM.coerceIn(1, MAX_ZOOM_CEILING)
        return object : XYTileSource(
            SOURCE_NAME,
            0,
            maxZoom,
            TILE_SIZE,
            TILE_ENDING,
            // The base-URL array is unused here: the whole address comes from the template.
            emptyArray(),
        ) {
            override fun getTileURLString(tileIndex: Long): String = urlTemplate.urlFor(
                zoom = MapTileIndex.getZoom(tileIndex),
                x = MapTileIndex.getX(tileIndex),
                y = MapTileIndex.getY(tileIndex),
            )
        }
    }

    /** osmdroid's own cap, and the limit `MAP_TILE_MAX_ZOOM` is checked against. */
    private const val MAX_ZOOM_CEILING = 22

    /** Tile edge in pixels, which every slippy-map provider ships at 256. */
    private const val TILE_SIZE = 256
}
