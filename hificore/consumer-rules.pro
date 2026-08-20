# reflection in ReflectionAudioEffect
-keep class org.nift4.audiofxfwd.OnParameterChangeListenerAdapter { *; }
-dontwarn android.media.audiofx.AudioEffect$OnParameterChangeListener

# reflection in AudioSystemHiddenApi
-keep class org.nift4.audiofxfwd.VolumeGroupCallbackAdapter { *; }
-dontwarn android.media.AudioManager$VolumeGroupCallback
-keep class org.nift4.audiosysfwd.AudioVolumeGroupCallbackAdapter { *; }
-dontwarn android.media.AudioSystem
-dontwarn android.media.INativeAudioVolumeGroupCallback
-dontwarn android.media.INativeAudioVolumeGroupCallback$Stub
-dontwarn android.media.audio.common.AudioVolumeGroupChangeEvent

# JNI
-keep class com.jwoolston.libusb.TransferCallback {
    public void onTransferComplete(com.jwoolston.libusb.AsyncTransfer, int);
    public void onTransferFailed(com.jwoolston.libusb.AsyncTransfer, com.jwoolston.libusb.LibusbError, int);
}
-keep class com.jwoolston.libusb.AsyncTransfer {
    long getNativeObject();
    public com.jwoolston.libusb.TransferCallback getCallback();
}
-keep class com.jwoolston.libusb.LibusbError {
    public static com.jwoolston.libusb.LibusbError fromNative(int);
}