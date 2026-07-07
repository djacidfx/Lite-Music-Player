/*
 *     Copyright (C) 2026 nift4
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

package org.akanework.gramophone.ui

import android.content.ContentUris
import android.content.Intent
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import kotlinx.coroutines.flow.flowOf
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.requireMediaStoreId
import org.akanework.gramophone.ui.adapters.SongAdapter
import org.akanework.gramophone.ui.adapters.Sorter

class SongPickerActivity : PickerActivity<MediaItem>() {
    override fun makeAdapter() =
        SongAdapter(
            null,
            null,
            rawOrderExposed = Sorter.Type.ByTitleAscending,
            isSubFragment = R.id.songs,
            fallbackContext = this
        )

    override fun getTitleStr() = getString(R.string.picker_activity)

    fun onSelected(item: MediaItem) {
        setResult(RESULT_OK, Intent().apply {
            setDataAndType(ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                item.requireMediaStoreId()), item.localConfiguration?.mimeType)
            setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        finish()
    }
}