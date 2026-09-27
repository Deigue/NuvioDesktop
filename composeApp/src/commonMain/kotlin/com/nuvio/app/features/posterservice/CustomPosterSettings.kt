package com.nuvio.app.features.posterservice

/** Which card shape a poster-service template is cut for. */
enum class CustomPosterShape(internal val placeholderValue: String) {
    /** The 2:3 poster every portrait card and the details hero show. */
    Portrait("poster"),
    /** A 16:9 poster with the title composited in, for landscape rows — see `MetaPreview.landscapePoster`. */
    Landscape("landscape"),
}

/**
 * The parts of the app the poster service can be applied to, mirroring upstream's per-screen
 * toggles (`CustomPosterScreen` in NuvioMobile) so the same choice syncs between the apps.
 *
 * [key] is upstream's stored/synced key and must not change. [synced] is false for screens only
 * this fork has: upstream's clients rewrite the synced set from their own enum, so a fork-only key
 * sent there would be dropped on their next push and switch the screen off here.
 */
enum class CustomPosterScreen(val key: String, val synced: Boolean = true) {
    /** Home catalog rows, See All, and filename-resolved cloud rows. */
    Home("home"),
    /** Continue Watching and Up Next. */
    ContinueWatching("continue_watching"),
    /** Collection folder screens. */
    Collections("collections"),
    /** Library, including Local Library and SIMKL library rows. */
    Library("library"),
    /** Search results and Discover-in-Search. */
    Search("search"),
    /** More Like This, collection parts, cast/crew credits, and studio/network browsing. */
    Details("details"),
    /** The Discover section (fork-only). */
    Discover("discover", synced = false);

    companion object {
        val ALL: Set<CustomPosterScreen> = entries.toSet()

        /**
         * A fresh setup: everything but Continue Watching, whose episode stills are usually the
         * better card. Upstream's wire format still reads an empty list as [ALL].
         */
        val DEFAULT: Set<CustomPosterScreen> = ALL - ContinueWatching

        /** Upstream stores an unset/empty selection, and so means every screen. */
        fun fromKeys(keys: Collection<String>?): Set<CustomPosterScreen> {
            if (keys.isNullOrEmpty()) return ALL
            return keys.mapNotNull { key -> entries.firstOrNull { it.key == key.trim() } }.toSet()
        }

        fun toKeys(screens: Set<CustomPosterScreen>): List<String> = entries.filter { it in screens }.map { it.key }
    }
}

/**
 * How the poster service treats Continue Watching, which alone has a third answer: its episode cards
 * normally show the episode still, and whether the user's poster art should outrank that is a
 * separate preference from whether the service applies there at all.
 */
enum class CustomPosterContinueWatchingMode {
    /** Continue Watching keeps its own art. The default: stills say which episode is next. */
    Off,
    /** Replaces the poster and backdrop only; episode stills still win (upstream's behavior). */
    BaseArt,
    /** Custom art first on every card, episode stills only as a fallback. */
    All,
}

/**
 * The user's custom poster service (PostersPlus, RPDB, a self-hosted service, …).
 *
 * Each template is a full URL with placeholders — see `customPosterUrl`. Either template may be
 * blank; a blank template simply leaves that shape on whatever art the row already had, except that
 * a portrait template naming `{shape}` also serves landscape cards (upstream's single-pattern form).
 * One switch covers both because the service is one decision: art is either routed through it or
 * it is not. [enabledScreens] then narrows where that decision applies.
 */
data class CustomPosterSettings(
    val enabled: Boolean = false,
    val posterUrlTemplate: String = "",
    val landscapeUrlTemplate: String = "",
    val enabledScreens: Set<CustomPosterScreen> = CustomPosterScreen.DEFAULT,
    /** With Continue Watching enabled, whether custom art outranks episode stills. Local only. */
    val continueWatchingOverStills: Boolean = false,
) {
    /**
     * The Continue Watching dropdown, as a view over [enabledScreens] (which is what syncs with
     * upstream's single on/off screen key) plus [continueWatchingOverStills].
     */
    val continueWatchingMode: CustomPosterContinueWatchingMode
        get() = when {
            CustomPosterScreen.ContinueWatching !in enabledScreens -> CustomPosterContinueWatchingMode.Off
            continueWatchingOverStills -> CustomPosterContinueWatchingMode.All
            else -> CustomPosterContinueWatchingMode.BaseArt
        }

    val hasPosterTemplate: Boolean
        get() = posterUrlTemplate.isNotBlank()

    val hasLandscapeTemplate: Boolean
        get() = landscapeUrlTemplate.isNotBlank()

    val hasAnyTemplate: Boolean
        get() = hasPosterTemplate || hasLandscapeTemplate

    /** Something would be routed through the service if asked. */
    val isActive: Boolean
        get() = enabled && hasAnyTemplate

    fun isActive(shape: CustomPosterShape): Boolean = enabled && templateFor(shape).isNotBlank()

    /** The poster template names `{shape}`, so it can serve landscape cards on its own. */
    val posterTemplateServesLandscape: Boolean
        get() = posterUrlTemplate.contains(SHAPE_PLACEHOLDER, ignoreCase = true)

    fun templateFor(shape: CustomPosterShape): String = when (shape) {
        CustomPosterShape.Portrait -> posterUrlTemplate
        CustomPosterShape.Landscape -> landscapeUrlTemplate.ifBlank {
            posterUrlTemplate.takeIf { posterTemplateServesLandscape }.orEmpty()
        }
    }

    /** These settings as seen from [screen]: switched off when the user excluded it. */
    fun forScreen(screen: CustomPosterScreen): CustomPosterSettings =
        if (!enabled || screen in enabledScreens) this else copy(enabled = false)

    /** The templates in play, for questions like "does any template need an IMDb id?". */
    internal fun activeTemplates(): List<String> =
        if (!enabled) emptyList() else listOf(posterUrlTemplate, landscapeUrlTemplate).filter { it.isNotBlank() }

    private companion object {
        const val SHAPE_PLACEHOLDER = "{shape"
    }
}
