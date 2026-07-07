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

package android.media;

import java.util.concurrent.Executor;

@SuppressWarnings("unused")
public class AudioManager {
    public AudioManager() {
        throw new UnsupportedOperationException("Stub!");
    }

    public void registerVolumeGroupCallback(Executor executor,
                                            VolumeGroupCallback callback) {
        throw new UnsupportedOperationException("Stub!");
    }

    public void unregisterVolumeGroupCallback(VolumeGroupCallback callback) {
        throw new UnsupportedOperationException("Stub!");
    }

    public abstract static class VolumeGroupCallback {
        public void onAudioVolumeGroupChanged(int group, int flags) {
            throw new UnsupportedOperationException("Stub!");
        }
    }
}