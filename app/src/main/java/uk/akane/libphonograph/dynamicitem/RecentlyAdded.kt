/*
 *     Copyright (C) 2025 The Gramophone authors
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

package uk.akane.libphonograph.dynamicitem

import androidx.media3.common.MediaItem
import uk.akane.libphonograph.items.Playlist
import uk.akane.libphonograph.items.addDate

class RecentlyAdded(minAddDate: Long, songList: List<MediaItem>) :
    Playlist(null, null, null, null, null, null) {
    private val rawList: List<MediaItem> = songList.sortedByDescending {
        it.mediaMetadata.addDate ?: -1
    }
    private var filteredList = filterList(minAddDate)
    var minAddDate: Long = minAddDate
        set(value) {
            if (field != value) {
                field = value
                filteredList = filterList(value)
            }
        }
    override val songList: List<MediaItem>
        get() = filteredList

    private fun filterList(minAddDate: Long): List<MediaItem> {
        return if (rawList.isEmpty()) rawList else rawList.let { l ->
            l.binarySearch {
                it.mediaMetadata.addDate?.let { minAddDate.compareTo(it) } ?: -1
            }.let {
                if (it >= 0) it else (-it - 1) // insertion point ~= first index
            }
        }.let {
            if (it > 0)
                rawList.subList(0, it)
            else
                listOf()
        }
    }

    override fun hashCode(): Int {
        var result = rawList.hashCode()
        result = 31 * result + minAddDate.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RecentlyAdded

        if (rawList != other.rawList) return false
        if (minAddDate != other.minAddDate) return false

        return true
    }
}