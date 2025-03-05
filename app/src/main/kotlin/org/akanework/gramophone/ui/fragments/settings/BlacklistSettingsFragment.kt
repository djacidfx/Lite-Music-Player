package org.akanework.gramophone.ui.fragments.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.ui.adapters.BlacklistFolderAdapter
import org.akanework.gramophone.ui.fragments.BaseFragment

class BlacklistSettingsFragment : BaseFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_blacklist_settings, container, false)
        val topAppBar = rootView.findViewById<MaterialToolbar>(R.id.topAppBar)
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        rootView.findViewById<AppBarLayout>(R.id.appbarlayout).enableEdgeToEdgePaddingListener()
        val folderArray = runBlocking { mainActivity.reader.foldersFlow.first().toMutableList() }
        folderArray.sort()

        topAppBar.setNavigationOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        val recyclerView = rootView.findViewById<RecyclerView>(R.id.recyclerview)
        recyclerView.enableEdgeToEdgePaddingListener()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = BlacklistFolderAdapter(this, folderArray, prefs)

        return rootView
    }
}