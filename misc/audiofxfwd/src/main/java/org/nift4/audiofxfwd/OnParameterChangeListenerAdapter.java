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

import android.media.audiofx.AudioEffect;

import java.lang.reflect.Method;

@SuppressWarnings("unused")
        /* package */ class OnParameterChangeListenerAdapter implements AudioEffect.OnParameterChangeListener {
    private final OnParameterChangeListener delegate;

    public OnParameterChangeListenerAdapter(OnParameterChangeListener delegate) {
        this.delegate = delegate;
    }

    @SuppressWarnings("BlockedPrivateApi")
    public static Method getSetter() throws NoSuchMethodException {
        return AudioEffect.class.getDeclaredMethod("setParameterListener",
                AudioEffect.OnParameterChangeListener.class);
    }

    @Override
    public void onParameterChange(AudioEffect effect, int status, byte[] param, byte[] value) {
        delegate.onParameterChange(effect, status, param, value);
    }
}
