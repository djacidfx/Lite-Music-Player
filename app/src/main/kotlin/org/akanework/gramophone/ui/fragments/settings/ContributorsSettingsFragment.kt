package org.akanework.gramophone.ui.fragments.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.akanework.gramophone.R
import org.akanework.gramophone.ui.fragments.BaseFragment
import org.akanework.gramophone.ui.fragments.BaseSettingFragment

class ContributorsSettingsFragment : BaseSettingFragment(
    R.string.settings_contributors, { ContributorsFragment() })

class ContributorsFragment : BaseFragment(null) {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return TextView(requireActivity()).also {
            it.text = requireContext().getString(R.string.settings_contributors_long)
        }
    }
}