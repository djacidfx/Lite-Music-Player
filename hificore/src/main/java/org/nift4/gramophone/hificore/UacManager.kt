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

package org.nift4.gramophone.hificore

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.getSystemService
import androidx.media3.common.util.Log
import org.nift4.gramophone.hificore.UacManager.Companion.UAC_PERMISSION_ACTION
import com.jwoolston.libusb.UsbDevice as LibUsbDevice
import com.jwoolston.libusb.UsbManager as LibUsbManager

class UacManager(private val context: Context) {
    companion object {
        private const val TAG = "Uac"
        private const val UAC_PERMISSION_ACTION =
            "org.nift4.gramophone.action.UAC_PERMISSION_GRANTED"
    }

    private val usbManager = context.getSystemService<UsbManager>()!!
    private val libUsbManager = LibUsbManager(context)
    private val openDevices = mutableListOf<LibUsbDevice>()
    private val attachDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isAttach = intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
            val isDetach = intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED
            val isPermState = intent.action == UAC_PERMISSION_ACTION
            if (isAttach || isDetach || isPermState) {
                val device = IntentCompat.getParcelableExtra(
                    intent,
                    UsbManager.EXTRA_DEVICE, UsbDevice::class.java
                )
                if (device == null) return
                val isPermGranted = isPermState && intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (isAttach || isPermGranted)
                    dispatchDeviceAddedCallbackIfNeeded(device)
                else if (isDetach)
                    dispatchDeviceDetachedCallbackIfNeeded(device)
            }
        }
    }

    init {
        ContextCompat.registerReceiver(context, attachDetachReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UAC_PERMISSION_ACTION)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        enumerateSoundcards()
    }

    private fun dispatchDeviceAddedCallbackIfNeeded(device: UsbDevice) {
        if (!isDeviceAudioEligible(device, true))
            return
        if (!usbManager.hasPermission(device))
            return
        enumerateSoundcards()
        // TODO: do something.
    }

    private fun dispatchDeviceDetachedCallbackIfNeeded(device: UsbDevice) {
        if (!isDeviceAudioEligible(device, false))
            return
        openDevices.removeAll { it.androidDevice == device }
        //enumerateSoundcards()
        // TODO: do something.
    }

    fun enumerateSoundcards() {
        /*usbManager.deviceList.values.filter { isDeviceAudioEligible(it, false) }
            .forEach {
                if (!usbManager.hasPermission(it)) {
                    val i = Intent(UAC_PERMISSION_ACTION)
                    i.setPackage(context.packageName)
                    val pi = PendingIntentCompat.getBroadcast(
                        context, 0x4ac2, i,
                        PendingIntent.FLAG_ONE_SHOT, false
                    )
                    usbManager.requestPermission(it, pi)
                    return@forEach
                }
                if (openDevices.find { it.androidDevice == it } != null)
                    return@forEach
                val deviceHandle = try {
                    libUsbManager.openDevice(it)
                } catch (e: Exception) {
                    Log.e(TAG, "failed to open $it", e)
                    return@forEach
                }
                openDevices.add(deviceHandle)
            }*/
    }

    private fun isDeviceAudioEligible(device: UsbDevice, allowLog: Boolean): Boolean {
        // we don't care about device class. as long as we have audio function, we can use it.
        val supportedConfigurations = mutableSetOf<Int>()
        for (configurationIndex in 0..<device.configurationCount) {
            val configuration = device.getConfiguration(configurationIndex)
            var hasAudioControl = false
            var hasAudioStreamingSink = false
            var hasAudioStreamingSource = false
            var hasMidiStreaming = false
            for (interfaceIndex in 0..<configuration.interfaceCount) {
                val iface = configuration.getInterface(interfaceIndex)
                if (iface.interfaceClass != UsbConstants.USB_CLASS_AUDIO) {
                    continue
                }
                if (iface.interfaceProtocol != 0x20 /* IP_VERSION_02_00 */) {
                    if (allowLog)
                        Log.e(
                            TAG,
                            "$device/$configuration has unsupported interface version $iface"
                        )
                    continue
                }
                when (iface.interfaceSubclass) {
                    0x01 /* AUDIOCONTROL */ -> hasAudioControl = true
                    0x02 /* AUDIOSTREAMING */ -> {
                        for (epIndex in 0..<iface.endpointCount) {
                            val ep = iface.getEndpoint(epIndex)
                            if (ep.attributes.shl(4).and(3) == 0) { // data EP
                                when (ep.direction) {
                                    UsbConstants.USB_DIR_IN -> hasAudioStreamingSource = true
                                    UsbConstants.USB_DIR_OUT -> hasAudioStreamingSink = true
                                }
                            }
                        }
                    }
                    0x03 /* MIDISTREAMING */ -> hasMidiStreaming = true
                    else -> {
                        if (allowLog)
                            Log.e(
                                TAG,
                                "$device/$configuration has unsupported interface subclass $iface"
                            )
                    }
                }
            }
            if (!hasAudioControl) {
                continue
            }
            if (!hasAudioStreamingSink) {
                if (allowLog) {
                    if (hasMidiStreaming) {
                        Log.i(
                            TAG, "$device/$configuration has no audio streaming " +
                                    "class, is MIDI device"
                        )
                    } else if (hasAudioStreamingSource) {
                        Log.i(TAG, "$device/$configuration has no audio streaming " +
                                "sink but has source, is microphone (not headset)")
                    } else {
                        Log.w(TAG, "$device/$configuration has no streaming class")
                    }
                }
                continue
            }
            supportedConfigurations.add(configurationIndex) // TODO: exit early here.
        }
        if (supportedConfigurations.isEmpty()) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            device.deviceClass == UsbConstants.USB_CLASS_VIDEO
        ) {
            Log.w(
                TAG, "eligible audio device is UVC device, missing camera " +
                        "permission to access, hence ignoring"
            )
            return false
        }
        return true
    }
}