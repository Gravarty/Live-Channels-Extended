package com.android.tv.ui.sidepanel

import android.view.View
import android.widget.Toast
import com.android.tv.R
import com.android.tv.TvApplication
import com.android.tv.TvSingletons
import com.android.tv.license.LicenseSideFragment
import com.android.tv.license.Licenses

/**
 * Einstellungen: Kanalliste anpassen, Kanalquellen, Lizenzen, Version.
 * Entfällt: Kindersicherung (nur System-App) und Trickplay-Schalter (nur eingebauter Tuner).
 */
class SettingsFragment : SideFragment<Item>() {

    override fun getTitle(): String = getString(R.string.side_panel_title_settings)

    override fun getItemList(): List<Item> {
        val items = ArrayList<Item>()
        val activity = mainActivity
        val sideFragmentManager = activity.overlayManager.sideFragmentManager

        val customizeChannelListItem = object : SubMenuItem(
            getString(R.string.settings_channel_source_item_customize_channels),
            getString(R.string.settings_channel_source_item_customize_channels_description),
            sideFragmentManager,
        ) {
            override fun getFragment(): SideFragment<*> = CustomizeChannelListFragment()

            override fun onBind(view: View) {
                super.onBind(view)
                setEnabled(false)
            }

            override fun onUpdate() {
                super.onUpdate()
                setEnabled(channelDataManager.channelCount != 0)
            }
        }
        customizeChannelListItem.setEnabled(false)
        items.add(customizeChannelListItem)

        val hasNewInput = TvSingletons.getSingletons(requireContext()).getSetupUtils().hasNewInput(activity.tvInputManagerHelper)
        items.add(object : ActionItem(
            getString(R.string.settings_channel_source_item_setup),
            if (hasNewInput) getString(R.string.settings_channel_source_item_setup_new_inputs) else null,
        ) {
            override fun onSelected() {
                closeFragment()
                activity.overlayManager.showSetupFragment()
            }
        })

        // Extended: "Feedback geben" entfernt

        // Extended: Anpassungen
        items.add(object : SubMenuItem(getString(R.string.settings_tweaks), sideFragmentManager) {
            override fun getFragment(): SideFragment<*> = TweaksFragment()
        })

        if (Licenses.hasLicenses(requireContext())) {
            items.add(object : SubMenuItem(getString(R.string.settings_menu_licenses), sideFragmentManager) {
                override fun getFragment(): SideFragment<*> = LicenseSideFragment()
            })
        }

        // Extended: "Interactive App Settings" entfernt (auf dem Gerät ohne Wirkung)

        val version = SimpleActionItem(
            getString(R.string.settings_menu_version), (activity.applicationContext as TvApplication).versionName)
        version.setClickable(false)
        items.add(version)
        return items
    }

    override fun onResume() {
        super.onResume()
        if (channelDataManager.areAllChannelsHidden()) {
            Toast.makeText(activity, R.string.msg_all_channels_hidden, Toast.LENGTH_SHORT).show()
        }
    }
}
