package com.android.tv.tweaks

import android.content.Context

/**
 * Extended: Anpassungen (Einstellungen → Anpassungen). Jeder Tweak ist standardmäßig aus.
 * Tweak entfernen: Schalter in TweaksFragment, Eintrag hier und die mit "Tweak:" markierten Stellen löschen.
 */
object Tweaks {
    private const val PREFS = "com.android.tv.extended.tweaks"

    // Tweak: Genre-Leiste im Programmführer komplett ausbauen
    private const val KEY_HIDE_GUIDE_GENRES = "hide_guide_genres"

    // Tweak: Senderlogo statt Sendungsbild in den Kanal-Tiles des Menüs
    private const val KEY_CHANNEL_CARD_LOGO = "channel_card_logo"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isGuideGenresHidden(context: Context): Boolean = prefs(context).getBoolean(KEY_HIDE_GUIDE_GENRES, false)

    fun setGuideGenresHidden(context: Context, hidden: Boolean) =
        prefs(context).edit().putBoolean(KEY_HIDE_GUIDE_GENRES, hidden).apply()

    fun isChannelCardLogo(context: Context): Boolean = prefs(context).getBoolean(KEY_CHANNEL_CARD_LOGO, false)

    fun setChannelCardLogo(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_CHANNEL_CARD_LOGO, enabled).apply()
}
