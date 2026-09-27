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

    // Tweak: OK öffnet die Programmübersicht, OK lang das Menü
    private const val KEY_OK_OPENS_GUIDE = "ok_opens_guide"

    // Tweak: Provider-Logo (Icon des TV-Inputs) im Kanal-Banner und in der Programmübersicht ausblenden
    private const val KEY_HIDE_PROVIDER_LOGO = "hide_provider_logo"

    // Tweak: Zurück muss zum Beenden zweimal gedrückt werden
    private const val KEY_CONFIRM_EXIT = "confirm_exit"

    // Tweak: Play/Pause beim Spulen spielt sofort normal ab, statt zu pausieren
    private const val KEY_PLAY_RESUMES_TRICK_PLAY = "play_resumes_trick_play"

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

    fun isOkOpensGuide(context: Context): Boolean = prefs(context).getBoolean(KEY_OK_OPENS_GUIDE, false)

    fun setOkOpensGuide(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_OK_OPENS_GUIDE, enabled).apply()

    fun isProviderLogoHidden(context: Context): Boolean = prefs(context).getBoolean(KEY_HIDE_PROVIDER_LOGO, false)

    fun setProviderLogoHidden(context: Context, hidden: Boolean) =
        prefs(context).edit().putBoolean(KEY_HIDE_PROVIDER_LOGO, hidden).apply()

    fun isConfirmExit(context: Context): Boolean = prefs(context).getBoolean(KEY_CONFIRM_EXIT, false)

    fun setConfirmExit(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_CONFIRM_EXIT, enabled).apply()

    fun isPlayResumesTrickPlay(context: Context): Boolean = prefs(context).getBoolean(KEY_PLAY_RESUMES_TRICK_PLAY, false)

    fun setPlayResumesTrickPlay(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_PLAY_RESUMES_TRICK_PLAY, enabled).apply()
}
