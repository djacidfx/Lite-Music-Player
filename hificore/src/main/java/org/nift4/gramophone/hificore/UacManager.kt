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
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.getSystemService
import androidx.media3.common.util.Log
import com.jwoolston.libusb.UsbConfiguration
import com.jwoolston.libusb.UsbConstants
import com.jwoolston.libusb.UsbDevice as LibUsbDevice
import com.jwoolston.libusb.UsbInterface
import com.jwoolston.libusb.UsbManager as LibUsbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class UacManager(private val context: Context) {
    companion object {
        private const val TAG = "Uac"
        private const val IP_VERSION_02_00 = 0x20
        private const val AUDIOCONTROL = 0x01
        private const val AUDIOSTREAMING = 0x02
        private const val MIDISTREAMING = 0x03
        private const val UAC_PERMISSION_ACTION =
            "org.nift4.gramophone.action.UAC_PERMISSION_GRANTED"
        private const val ENABLE_UAC = false
    }

    private val usbManager = context.getSystemService<UsbManager>()!!
    private val libUsbManager = CoroutineScope(Dispatchers.Default).async { LibUsbManager(context) }
    private val openDevices = mutableListOf<LibUsbDevice>()
    private val attachDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isAttach = intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
            val isDetach = intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED
            val isPermState = intent.action == UAC_PERMISSION_ACTION
            Log.i(TAG, "received $intent")
            if (isAttach || isDetach || isPermState) {
                val device = IntentCompat.getParcelableExtra(
                    intent,
                    UsbManager.EXTRA_DEVICE, UsbDevice::class.java
                )
                if (device == null) {
                    Log.e(TAG, "received $intent with NULL device")
                    return
                }
                val isPermGranted = isPermState && intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (isAttach || isPermGranted)
                    dispatchDeviceAddedCallbackIfNeeded(device)
                else if (isDetach)
                    dispatchDeviceDetachedCallbackIfNeeded(device)
                else
                    Log.i(TAG, "usb permission denied")
            }
        }
    }

    init {
        ContextCompat.registerReceiver(context, attachDetachReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UAC_PERMISSION_ACTION)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        CoroutineScope(Dispatchers.Default).launch {
            libUsbManager.await()
            enumerateSoundcards()
        }
    }

    private fun dispatchDeviceAddedCallbackIfNeeded(device: UsbDevice) {
        if (!isDeviceAudioEligible(device, true))
            return
        if (!usbManager.hasPermission(device)) {
            if (ENABLE_UAC)
                requestPermission(device)
            return
        }
        CoroutineScope(Dispatchers.Default).launch {
            libUsbManager.await()
            enumerateSoundcards()
        }
        // TODO: do something.
    }

    private fun dispatchDeviceDetachedCallbackIfNeeded(device: UsbDevice) {
        if (!isDeviceAudioEligible(device, false))
            return
        openDevices.removeAll {
            val match = it.androidDevice == device
            if (match) {
                Log.i(TAG, "closing $it because disconnected")
                it.close()
            }
            match
        }
        //enumerateSoundcards()
        // TODO: do something.
    }

    private fun handleDeviceOpened(device: LibUsbDevice) {
        var selectedInterface: Pair<UsbConfiguration, Pair<UsbInterface, UsbInterface>>? = null
        config@for (configurationIndex in 0..<device.configurationCount) {
            val configuration = device.getConfiguration(configurationIndex)
            iad@for (interfaceAssociationIndex in 0..<configuration.interfaceAssociationCount) {
                val interfaceAssociation = configuration.getInterfaceAssociation(interfaceAssociationIndex)
                if (interfaceAssociation.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    interfaceAssociation.interfaceProtocol == IP_VERSION_02_00) {
                    val audioControlInterfaceNum = interfaceAssociation.firstInterface
                    if (interfaceAssociation.interfaceCount < 2)
                        continue@iad // AUDIOCONTROL alone is present, no streaming at all
                    val firstStreamingInterface = interfaceAssociation.firstInterface + 1
                    val lastStreamingInterface = interfaceAssociation.firstInterface +
                            interfaceAssociation.interfaceCount - 1
                    var lastAudioStreamingInterface = audioControlInterfaceNum
                    for (i in (firstStreamingInterface..lastStreamingInterface).reversed()) {
                        val streamingInterface = configuration.getInterface(i, 0)
                        if (streamingInterface.interfaceSubclass == AUDIOSTREAMING) {
                            lastAudioStreamingInterface = i
                            break
                        }
                    }
                    if (lastAudioStreamingInterface == audioControlInterfaceNum) {
                        // Only MIDISTREAMING is present
                        continue@iad
                    }
                    val audioControlInterface = configuration.getInterface(audioControlInterfaceNum, 0)
                    Log.i(TAG, "found IAD ${interfaceAssociation.name} that implements UAC2 in configuration $configurationIndex")
                    for (i in (firstStreamingInterface..lastStreamingInterface)) {
                        // skip alt setting 0 (idle)
                        for (j in 1..<configuration.getAltSettingCount(i)) {
                            val streamingInterface = configuration.getInterface(i, j)
                            if (streamingInterface.extra[6] == 1.toByte() &&
                                streamingInterface.extra.last() == 16.toByte()) {
                                selectedInterface = configuration to (audioControlInterface to streamingInterface)
                                Log.i("hi", "iface $i alt $j: $streamingInterface")
                                break@config
                            }
                        }
                    }
                } else continue@iad
            }
        }
        if (selectedInterface == null)
            return
        val ret = device.claimInterfaceOnConfiguration(selectedInterface.first,
            selectedInterface.second.first, true)
        Log.i("hi", "claim AC $ret")
        val ret2 = device.claimInterfaceOnConfiguration(selectedInterface.first,
            selectedInterface.second.second, true)
        Log.i("hi", "claim AS $ret2")

        val rate = byteArrayOf(
            0x44.toByte(),
            0xAC.toByte(),
            0x00,
            0x00
        )

        // TODO: watch Active Alternate Setting Control
        // TODO: honor Valid Alternate Settings Control
        // TODO: Terminal Connector Control Interrupt support for jack detection
        //https://learn.microsoft.com/en-us/windows-hardware/drivers/audio/usb-2-0-audio-drivers#class-requests-and-interrupt-data-messages
        val r: Int = device.controlTransfer(
            0x21,
            0x01,
            0x0100,
            0x2900,
            rate,
            0,
            rate.size,
            1000
        )

        // to find the endpoint, according to USB 2.0 specification chapter 9.6.6, the feedback EP
        // for a data EP is the first opposite-direction EP with the same _or lower_ number.
        // so we first choose a data EP and can then compute the feedback EP from that information.
        // the feedback EP might be a Feedback-only EP or an implicit feedback data EP, we don't
        // support the latter, and should not select such alt settings I suppose (TODO).
        // TODO https://github.com/torvalds/linux/blob/8d3ae59288f1e7d58d76558a6ee96d533bc5019f/sound/usb/pcm.c#L375
        //  why does Linux do this? is this carried over from UAC1 (need to check old spec!)?

        // Basic idea: the feedback number is samples(!)/microframe. Isochronous means 1 microframe
        // is one packet, so it's how much samples (integer samples only) one packet should be.
        // And the question of how many packets are queued at once, is simply based on OS scheduling
        // constraints, as the packets need to return from flight, filled up again, and submitted,
        // before the USB controller buffer is empty.
        // (It's important to remember that 10 transfers with each 1 iso packet, and 1 transfer with
        // 10 iso packets, are exactly the same on the USB bus. It's just a question of how often we
        // get woken up to refill buffers! So the tuning of packets per transfer is a question of
        // efficiency, while the question of packet queue size is a question of will it underflow or
        // not. The queue can also be too big: then the feedback loop becomes big enough the device
        // can no longer compensate because it's internal buffer is empty or full, and xrun occurs.)
        // For each SINGLE iso packet(!!! not per transfer) we do:
        //    We clear the accumulated number's decimal part to just keep the fraction to carry over.
        //    We read the feedback and add it to the accumulated number. The accumulated number is
        //    exactly how much samples we need to send in this packet. (Except the fraction which is
        //    kept for next time)
        // linux uses this fractional accumulator:
        /*
         * 	phase = (ep->phase & 0xffff) + (ep->freqm << ep->datainterval);
         * 	ret = min(phase >> 16, ep->maxframesize);
         * 	if (avail && ret >= avail)
         * 		ret = -EAGAIN; /*this means we wait until we get new PCM */
         * 	else
         * 		ep->phase = phase;
         * 	return ret;
         */
        // but they do just use whatever the latest freqm retrieved from the device is, so feedback
        // poll it is.
        // repeat this until we have enough iso packets to fill one transfer, and send it out! then,
        // once one of the earlier transfers is done, prepare the next one. (Notably it should
        // always use the latest feedback value and not use any averages or similar.)

        // the issue with freqm polling is that it seems hard to time this to be every n microframes
        // as we don't have any kind of accuracy with submit_transfer doing ASAP timing, and Linux
        // has urb->interval and I think we don't :( we are forced to use the bInterval of the EP,
        // as usbdevfs for some reason doesn't support reducing interval. We can however batch
        // exactly that many isoc packets into a transfer and just read the latest one. Reason we
        // can't just provide less packets is that the queue will go idle, and after idle queue, the
        // first packet will be executed instantly. So even not setting ASAP and setting start_frame
        // will behave the exact same (as we were polling slower than bInterval and thus queue
        // starved and went idle).
        // TODO smart parsing of freqm if I'm bored?
        //  https://github.com/torvalds/linux/blob/8d3ae59288f1e7d58d76558a6ee96d533bc5019f/sound/usb/endpoint.c#L1877

        // For synchronous, the clock source is the USB clock. That means we send constant amount of
        // samples per packet based on the assumption we send exactly samples for 125us, per packet.
        // Adaptive sinks will essentially achieve the same result when we use the same strategy.
        // The basic assumption for the above is that decoder is faster than real-time to ensure we
        // always have enough data. We do NOT use an internal buffer, we use transfers as the buffer.
        // If we read too many iso packets into one transfer at once, we would starve the decoder.
        // If we do not read enough in, we waste CPU time with repeatedly having overhead of
        // decoding and submitting transfer, so we optimally want as much as possible that would not
        // starve decoder (but a lot would mean high packet queue size, which means high audio
        // latency, which we don't want). The total packet queue (=buffer size, essentially) should
        // be tuned for avoiding USB xrun if we are too slow to generate new packets, this means it
        // should be some higher multiple of transfer queue to make sure if we are late once or
        // twice we don't instantly xrun (maybe 4 times). It should also not be too high due to
        // audio latency as previously mentioned.
        // We can say 4 transfers and as such (packet queue size / 4) packets per transfer, with
        // packet queue size being size of audio buffer. If audio buffer is too small, we will xrun,
        // and if it's too big we simply have high latency.
        // It just occurred to me that pause/flush can be implemented like that too! By cancelling
        // transfers. So we can go safe and queue a lot of buffers and just cancel some transfers if
        // we don't feel like it anymore. (This does not apply to async mode because the feedback
        // must be as low-latency as possible, so the packet queue must be as small as possible and
        // refilled in real-time-safe environment, that is, native thread. But because decoder isn't
        // real-time-safe, we use an internal buffer for async mode. This internal buffer must be
        // big enough to compensate for non-real-time decoder, while the packet queue is small to
        // keep feedback latency small. We can also choose to use less transfers if we use a
        // real-time-safe transfer filling environment.)
        // TODO: to not ruin the real-time-safe libusb callback thread we have now: dispatch
        //  JNI-based callbacks to another thread (how?).
        // TODO: then, implement purely event-handler-refill based feedback polling in C(++).
        // TODO: lastly, implement some buffer (maybe ring? idk yet) that Java can write from, and
        //  event handler can read _without blocking_. also implement cancel even in this LL case.
        // TODO: and then we implement adaptive/synchronous buffer filling in java as seperate
        //  AudioOutput, there's no reason to overcomplicate it by making it C++ too :) especially
        //  as it is a mostly seperate codepath.


    }

    suspend fun enumerateSoundcards() {
        if (!ENABLE_UAC) return
        usbManager.deviceList.values.filter { isDeviceAudioEligible(it, false) }
            .forEach {
                if (!usbManager.hasPermission(it)) {
                    requestPermission(it)
                    return@forEach
                }
                if (openDevices.find { it.androidDevice == it } != null)
                    return@forEach
                val deviceHandle = try {
                    libUsbManager.await().openDevice(it)
                } catch (e: Exception) {
                    Log.e(TAG, "failed to open $it", e)
                    return@forEach
                }
                openDevices.add(deviceHandle)
                handleDeviceOpened(deviceHandle)
            }
    }

    private fun requestPermission(device: UsbDevice) {
        val i = Intent(UAC_PERMISSION_ACTION)
        i.setPackage(context.packageName)
        val pi = PendingIntentCompat.getBroadcast(
            context, 0x4ac2, i,
            PendingIntent.FLAG_ONE_SHOT, true
        )
        usbManager.requestPermission(device, pi)
    }

    private fun isDeviceAudioEligible(device: UsbDevice, allowLog: Boolean): Boolean {
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
                if (iface.interfaceProtocol != IP_VERSION_02_00) {
                    if (allowLog)
                        Log.e(
                            TAG,
                            "$device/$configuration has unsupported interface version $iface"
                        )
                    continue
                }
                when (iface.interfaceSubclass) {
                    AUDIOCONTROL -> hasAudioControl = true
                    AUDIOSTREAMING -> {
                        for (epIndex in 0..<iface.endpointCount) {
                            val ep = iface.getEndpoint(epIndex)
                            if (ep.attributes.shl(4).and(3) == UsbConstants.USB_ISO_USAGE_TYPE_DATA) {
                                when (ep.direction) {
                                    UsbConstants.USB_DIR_IN -> hasAudioStreamingSource = true
                                    UsbConstants.USB_DIR_OUT -> hasAudioStreamingSink = true
                                }
                            }
                        }
                    }
                    MIDISTREAMING -> hasMidiStreaming = true
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
        return false
    }
}