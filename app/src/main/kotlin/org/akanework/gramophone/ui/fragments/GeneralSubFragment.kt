/*
 *     Copyright (C) 2024 Akane Foundation
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.logic.ui.MyRecyclerView
import org.akanework.gramophone.ui.adapters.SongAdapter
import org.akanework.gramophone.ui.adapters.Sorter
import uk.akane.libphonograph.dynamicitem.RecentlyAdded

/**
 * GeneralSubFragment:
 *   Inherited from [BaseFragment]. Sub fragment of all
 * possible item types. TODO: Artist / AlbumArtist
 *
 * @see BaseFragment
 * @author AkaneTan, nift4
 */
@androidx.annotation.OptIn(UnstableApi::class)
class GeneralSubFragment : BaseFragment(true) {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {

        lateinit var itemList: Flow<List<MediaItem>?>

        val rootView = inflater.inflate(R.layout.fragment_general_sub, container, false)
        val topAppBar = rootView.findViewById<MaterialToolbar>(R.id.topAppBar)
        val collapsingToolbarLayout =
            rootView.findViewById<CollapsingToolbarLayout>(R.id.collapsingtoolbar)
        val recyclerView = rootView.findViewById<MyRecyclerView>(R.id.recyclerview)
        val appBarLayout = rootView.findViewById<AppBarLayout>(R.id.appbarlayout)
        appBarLayout.enableEdgeToEdgePaddingListener()

        val bundle = requireArguments()
        val itemType = bundle.getInt("Item")
        val id = bundle.getString("Id")?.toLong()

        val title: Flow<String>

        var helper: Sorter.NaturalOrderHelper<MediaItem>? = null

        when (itemType) {
            R.id.album -> {
                val item = mainActivity.reader.albumListFlow.map { it.find { it.id == id }
                    ?: null.also { requireActivity().supportFragmentManager.popBackStack() } }
                title = item.map { it?.title ?: requireContext().getString(R.string.unknown_album) }
                itemList = item.map { it?.songList }
                helper =
                    Sorter.NaturalOrderHelper {
                        it.mediaMetadata.trackNumber?.plus(
                            it.mediaMetadata.discNumber?.times(1000) ?: 0
                        ) ?: 0
                    }
            }

            /*R.id.artist -> {
                val item = libraryViewModel.artistItemList.value!![position]
                title = item.title ?: requireContext().getString(R.string.unknown_artist)
                itemList = item.songList
            } TODO */

            R.id.genres -> {
                // Genres
                val item = mainActivity.reader.genreListFlow.map { it.find { it.id == id }
                    ?: null.also { requireActivity().supportFragmentManager.popBackStack() } }
                title = item.map { it?.title ?: requireContext().getString(R.string.unknown_genre) }
                itemList = item.map { it?.songList }
            }

            R.id.dates -> {
                // Dates
                val item = mainActivity.reader.dateListFlow.map { it.find { it.id == id }
                    ?: null.also { requireActivity().supportFragmentManager.popBackStack() } }
                title = item.map { it?.title ?: requireContext().getString(R.string.unknown_year) }
                itemList = item.map { it?.songList }
            }

            /*R.id.album_artist -> {
                // Album artists
                val item = libraryViewModel.albumArtistItemList.value!![position]
                title = item.title ?: requireContext().getString(R.string.unknown_artist)
                itemList = item.songList
            } TODO */

            R.id.playlist -> {
                // Playlists
                val item = mainActivity.reader.playlistListFlow.map { it.find { it.id == id }
                    ?: null.also { requireActivity().supportFragmentManager.popBackStack() } }
                title = item.map {
                    if (it is RecentlyAdded) {
                        requireContext().getString(R.string.recently_added)
                    } else {
                        it?.title ?: requireContext().getString(R.string.unknown_playlist)
                    }
                }
                itemList = item.map { it?.songList }
                helper = Sorter.NaturalOrderHelper { runBlocking { itemList.first() }?.indexOf(it) ?: 0 }
            }

            else -> throw IllegalArgumentException()
        }

        lifecycleScope.launch {
            title.collect {
                withContext(Dispatchers.Main) {
                    // Show title text.
                    collapsingToolbarLayout.title = it
                }
            }
        }

        val songAdapter =
            SongAdapter(
                this,
                itemList,
                canSort = true,
                helper,
                ownsView = true,
                isSubFragment = true
            )

        recyclerView.enableEdgeToEdgePaddingListener()
        recyclerView.setAppBar(appBarLayout)
        recyclerView.adapter = songAdapter.concatAdapter

        // Build FastScroller.
        recyclerView.fastScroll(songAdapter, songAdapter.itemHeightHelper)

        topAppBar.setNavigationOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        return rootView
    }
}
