package com.android.tv.ui.sidepanel

import com.android.tv.R
import com.android.tv.tweaks.Tweaks

/** Extended: Anpassungen. Ein Schalter pro Tweak (siehe [Tweaks]). */
class TweaksFragment : SideFragment<Item>() {

    override fun getTitle(): String = getString(R.string.settings_tweaks)

    override fun getItemList(): List<Item> = listOf(
        // Tweak: Genre-Leiste in der Programmübersicht
        object : SwitchItem(
            getString(R.string.tweak_hide_guide_genres), getString(R.string.tweak_hide_guide_genres),
            getString(R.string.tweak_hide_guide_genres_description),
        ) {
            override fun onUpdate() {
                super.onUpdate()
                setChecked(Tweaks.isGuideGenresHidden(requireContext()))
            }

            override fun onSelected() {
                super.onSelected()
                Tweaks.setGuideGenresHidden(requireContext(), isChecked)
            }
        },
        // Tweak: Senderlogo in den Kanal-Tiles
        object : SwitchItem(
            getString(R.string.tweak_channel_card_logo), getString(R.string.tweak_channel_card_logo),
            getString(R.string.tweak_channel_card_logo_description),
        ) {
            override fun onUpdate() {
                super.onUpdate()
                setChecked(Tweaks.isChannelCardLogo(requireContext()))
            }

            override fun onSelected() {
                super.onSelected()
                Tweaks.setChannelCardLogo(requireContext(), isChecked)
            }
        },
        // Tweak: Hoch/Runter öffnet die Senderliste
        object : SwitchItem(
            getString(R.string.tweak_dpad_channel_list), getString(R.string.tweak_dpad_channel_list),
            getString(R.string.tweak_dpad_channel_list_description),
        ) {
            override fun onUpdate() {
                super.onUpdate()
                setChecked(Tweaks.isDpadChannelList(requireContext()))
            }

            override fun onSelected() {
                super.onSelected()
                Tweaks.setDpadChannelList(requireContext(), isChecked)
            }
        },
        // Tweak: OK öffnet die Programmübersicht
        object : SwitchItem(
            getString(R.string.tweak_ok_opens_guide), getString(R.string.tweak_ok_opens_guide),
            getString(R.string.tweak_ok_opens_guide_description),
        ) {
            override fun onUpdate() {
                super.onUpdate()
                setChecked(Tweaks.isOkOpensGuide(requireContext()))
            }

            override fun onSelected() {
                super.onSelected()
                Tweaks.setOkOpensGuide(requireContext(), isChecked)
            }
        },
        // Tweak: Provider-Logo verstecken
        object : SwitchItem(
            getString(R.string.tweak_hide_provider_logo), getString(R.string.tweak_hide_provider_logo),
            getString(R.string.tweak_hide_provider_logo_description),
        ) {
            override fun onUpdate() {
                super.onUpdate()
                setChecked(Tweaks.isProviderLogoHidden(requireContext()))
            }

            override fun onSelected() {
                super.onSelected()
                Tweaks.setProviderLogoHidden(requireContext(), isChecked)
            }
        },
    )
}
