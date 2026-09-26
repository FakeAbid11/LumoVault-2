package com.lumovault.app.domain.map

/**
 * A tile URL template, and whether it can be used at all.
 *
 * osmdroid's `XYTileSource` builds a URL as `base + z + "/" + x + "/" + y + ending`, which fits a server laid
 * out that way and no other; the providers worth using — including the ones that require a key in the query
 * string — are `{z}/{x}/{y}` templates instead. So the template is applied here, in pure code, rather than
 * being left to string concatenation in the adapter: that is the one piece of the map that can be tested, and
 * a wrong tile URL fails as a grey map with no error anywhere, which is not a thing a device test would catch.
 *
 * A template that does not name all three coordinates is rejected rather than repaired. `https://example/`
 * silently becoming "all tiles are the same image" is worse than a map that says it has no provider.
 */
data class TileTemplate(val raw: String) {

    /** Whether this string can address a tile at all. */
    val isUsable: Boolean
        get() = ZOOM in raw && X in raw && Y in raw &&
            raw.isNotBlank() && raw.none { it == '"' || it.code == BACKSLASH_CODE || it == '$' } &&
            (raw.startsWith("https://") || raw.startsWith("http://"))

    /**
     * The address of one tile.
     *
     * [y] is osmdroid's index within the zoom, which counts from the top — the same direction the template
     * convention uses, so no flip is applied here, and the tests pin that rather than assuming it.
     */
    fun urlFor(zoom: Int, x: Int, y: Int): String =
        raw.replace(ZOOM, zoom.toString())
            .replace(X, x.toString())
            .replace(Y, y.toString())

    /** Attribution a provider requires to be shown, or null when the build configured none. */
    companion object {
        const val ZOOM = "{z}"
        const val X = "{x}"
        const val Y = "{y}"

        /** Written as a code point so this file survives the generator that next touches it. */
        private const val BACKSLASH_CODE = 92

        /** An unusable template is no template: the map renders without tiles and says so. */
        fun of(raw: String): TileTemplate? = TileTemplate(raw.trim()).takeIf { it.isUsable }
    }
}
