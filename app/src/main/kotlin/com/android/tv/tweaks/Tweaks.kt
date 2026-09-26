package com.android.tv.tweaks

import android.content.Context

/**
 * Extended: Anpassungen (Einstellungen → Anpassungen). Jeder Tweak ist standardmäßig aus.
 * Tweak entfernen: Schalter in TweaksFragment, Eintrag hier und die mit "Tweak:" markierten Stellen löschen.
 */
object Tweaks {
    private const val PREFS = "com.android.tv.extended.tweaks"

    // Tweak: Genre-Leiste in der Programmübersicht komplett ausbauen
    private const val KEY_HIDE_GUIDE_GENRES = "hide_guide_genres"

    // Tweak: Senderlogo statt Sendungsbild in den Kanal-Tiles des Menüs
    private const val KEY_CHANNEL_CARD_LOGO = "channel_card_logo"

    // Tweak: Hoch/Runter öffnet die Senderliste statt direkt umzuschalten
    private const val KEY_DPAD_CHANNEL_LIST = "dpad_channel_list"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isGuideGenresHidden(context: Context): Boolean = prefs(context).getBoolean(KEY_HIDE_GUIDE_GENRES, false)

    fun setGuideGenresHidden(context: Context, hidden: Boolean) =
        prefs(context).edit().putBoolean(KEY_HIDE_GUIDE_GENRES, hidden).apply()

    fun isChannelCardLogo(context: Context): Boolean = prefs(context).getBoolean(KEY_CHANNEL_CARD_LOGO, false)

    fun setChannelCardLogo(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_CHANNEL_CARD_LOGO, enabled).apply()

    fun isDpadChannelList(context: Context): Boolean = prefs(context).getBoolean(KEY_DPAD_CHANNEL_LIST, false)

    fun setDpadChannelList(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_DPAD_CHANNEL_LIST, enabled).apply()
}
