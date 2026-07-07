/*
 *     Copyright (C) 2025 nift4
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

package org.nift4.audiofxfwd;

import android.media.AudioManager;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;

/* package */ class VolumeGroupCallbackAdapter extends AudioManager.VolumeGroupCallback {
    private final VolumeGroupCallback delegate;

    public VolumeGroupCallbackAdapter(VolumeGroupCallback delegate) {
        this.delegate = delegate;
    }

    @SuppressWarnings("PrivateApi")
    public static Method getAdd() throws NoSuchMethodException {
        return AudioManager.class.getDeclaredMethod("registerVolumeGroupCallback",
                Executor.class, AudioManager.VolumeGroupCallback.class);
    }

    @SuppressWarnings("PrivateApi")
    public static Method getRemove() throws NoSuchMethodException {
        return AudioManager.class.getDeclaredMethod("unregisterVolumeGroupCallback",
                AudioManager.VolumeGroupCallback.class);
    }

    @Override
    public void onAudioVolumeGroupChanged(int group, int flags) {
        delegate.onAudioVolumeGroupChanged(group, flags);
    }
}
