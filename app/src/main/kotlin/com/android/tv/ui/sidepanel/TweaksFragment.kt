package com.android.tv.ui.sidepanel

import com.android.tv.R
import com.android.tv.tweaks.Tweaks

/** Extended: Anpassungen. Ein Schalter pro Tweak (siehe [Tweaks]). */
class TweaksFragment : SideFragment<Item>() {

    override fun getTitle(): String = getString(R.string.settings_tweaks)

    override fun getItemList(): List<Item> = listOf(
        // Tweak: Genre-Leiste im Programmführer
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
    )
}
