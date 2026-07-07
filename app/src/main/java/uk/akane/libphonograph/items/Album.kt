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

package uk.akane.libphonograph.items

import android.net.Uri
import androidx.media3.common.MediaItem

interface Album : Item {
    override val id: Long?
    override val title: String?
    override val songList: List<MediaItem>
    val albumArtist: String?
    val albumArtistId: Long?
    val albumYear: Int? // Last year
    val albumAddDate: Long?
    val albumModifiedDate: Long?
    val cover: Uri?
}