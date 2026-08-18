package com.jwoolston.libusb;

/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.hardware.usb.UsbDeviceConnection;

import androidx.annotation.NonNull;

import com.jwoolston.libusb.async.AsyncTransfer;
import com.jwoolston.libusb.util.Preconditions;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;

/**
 * This class represents a USB device attached to the android device with the android device acting as the USB host.
 * Each device contains one or more {@link UsbInterface}s, each of which contains a number of {@link UsbEndpoint}s
 * (the channels via which data is transmitted over USB).
 * <p>
 * <p> This class contains information (along with {@link UsbInterface} and {@link UsbEndpoint}) that describes the
 * capabilities of the USB device. To communicate with the device, you use {@code UsbRequest} to
 * send and receive data on an endpoint. {@link UsbDevice#controlTransfer} is
 * used for control requests on endpoint zero.
 * <p>
 */
public class UsbDevice {

    private final UsbManager manager;
    final @NotNull String name;
    final @Nullable String manufacturerName;
    final @Nullable String productName;
    final @NotNull String version;
    final @NotNull String serialNumber;
    final @NotNull LibusbSpeed speed;

    final int vendorId;
    final int productId;
    final int deviceClass;
    final int subclass;
    final int protocol;

    private final UsbDeviceConnection connection;

    private long nativeObject;

    /**
     * All configurations for this device, only null during creation
     */
    @Nullable UsbConfiguration[] configurations;
    /**
     * All interfaces on the device. Initialized on first call to getInterfaceList
     */
    @Nullable UsbInterface[]     interfaces;

    @NonNull
    private final android.hardware.usb.UsbDevice device;

    @NonNull
    public android.hardware.usb.UsbDevice getAndroidDevice() {
        return device;
    }

    /**
     * Returns a unique integer ID for the device. This is a convenience for clients that want to
     * use an integer to represent the device, rather than the device name. IDs are not persistent
     * across USB disconnects.
     *
     * @return the device ID
     */
    public int getDeviceId() {
        return device.getDeviceId();
    }

    /**
     * UsbDevice should only be instantiated by UsbManager implementation
     */
    UsbDevice(@NonNull UsbManager manager, @NonNull android.hardware.usb.UsbDevice device, @NonNull UsbDeviceConnection connection) {
        this.connection = connection;
        this.nativeObject = wrapDevice(manager.getNativeObject(), connection.getFileDescriptor());
        Preconditions.checkArgument(nativeObject != 0, "UsbDevice initialization failed.");
        this.manager = manager;
        this.device = device;
        name = device.getDeviceName();

        LibUsbDeviceDescriptor descriptor = LibUsbDeviceDescriptor.getDeviceDescriptor(this);
        // This involves a lot of cross JNI calls but is safer across multiple platforms than byte
        // buffer access so we don't have to deal with padding/alignment
        vendorId = descriptor.getVendorId();
        productId = descriptor.getProductId();
        deviceClass = descriptor.getDeviceClass();
        subclass = descriptor.getDeviceSubclass();
        protocol = descriptor.getDeviceProtocol();

        manufacturerName = nativeGetManufacturerString(nativeObject, descriptor.getNativeObject());
        productName = nativeGetProductNameString(nativeObject, descriptor.getNativeObject());
        version = nativeGetDeviceVersion(descriptor.getNativeObject());

        speed = LibusbSpeed.fromNative(nativeGetDeviceSpeed(nativeObject, descriptor.getNativeObject()));
        descriptor.destroy();

        serialNumber = connection.getSerial();

        final int numConfigurations = nativeGetConfigurationCount(getNativeObject());
        @SuppressWarnings({"ConstantConditions"})
        final UsbConfiguration[] configurations = new UsbConfiguration[numConfigurations];
        for (int i = 0; i < numConfigurations; ++i) {
            configurations[i] = UsbConfiguration.fromNativeObject(this, i);
        }
        this.configurations = configurations;
    }

    static void initialize() {
        if (!nativeInitialize()) {
            throw new RuntimeException("Failed to initialize native layer for UsbDevice.");
        }
    }

    /**
     * Returns the name of the device. In the standard implementation, this is the path of the device file for the
     * device in the usbfs file system.
     *
     * @return the device name
     */
    @NotNull
    public String getDeviceName() {
        return name;
    }

    /**
     * Returns the manufacturer name of the device.
     *
     * @return the manufacturer name, or {@code null} if the property could not be read
     */
    @Nullable
    public String getManufacturerName() {
        return manufacturerName;
    }

    /**
     * Returns the product name of the device.
     *
     * @return the product name, or {@code null} if the property could not be read
     */
    @Nullable
    public String getProductName() {
        return productName;
    }

    /**
     * Returns the version number of the device.
     *
     * @return the device version
     */
    @NotNull
    public String getVersion() {
        return version;
    }

    /**
     * Returns the serial number of the device.
     *
     * @return the serial number name, or {@code null} if the property could not be read
     */
    @NotNull
    public String getSerialNumber() {
        return serialNumber;
    }

    /**
     * Returns the connection speed of the device.
     *
     * @return the connection speed.
     */
    @NotNull
    public LibusbSpeed getDeviceSpeed() {
        return speed;
    }

    /**
     * Returns a vendor ID for the device.
     *
     * @return the device vendor ID
     */
    public int getVendorId() {
        return vendorId;
    }

    /**
     * Returns a product ID for the device.
     *
     * @return the device product ID
     */
    public int getProductId() {
        return productId;
    }

    /**
     * Returns the devices's class field.
     * Some useful constants for USB device classes can be found in {@link UsbConstants}.
     *
     * @return the devices's class
     */
    public int getDeviceClass() {
        return deviceClass;
    }

    /**
     * Returns the device's subclass field.
     *
     * @return the device's subclass
     */
    public int getDeviceSubclass() {
        return subclass;
    }

    /**
     * Returns the device's protocol field.
     *
     * @return the device's protocol
     */
    public int getDeviceProtocol() {
        return protocol;
    }

    /**
     * Returns the number of {@link UsbConfiguration}s this device contains.
     *
     * @return the number of configurations
     */
    public int getConfigurationCount() {
        return configurations.length;
    }

    /**
     * Returns the {@link UsbConfiguration} at the given index.
     *
     * @return the configuration
     */
    @NotNull
    public UsbConfiguration getConfiguration(int index) {
        return configurations[index];
    }

    @NotNull
    private UsbInterface[] getInterfaceList() {
        if (interfaces == null) {
            int configurationCount = configurations.length;
            int interfaceCount = 0;
            for (UsbConfiguration configuration : configurations) {
                interfaceCount += configuration.getInterfaceCount();
            }
            interfaces = new UsbInterface[interfaceCount];
            int offset = 0;
            for (int i = 0; i < configurationCount; i++) {
                UsbConfiguration configuration = configurations[i];
                interfaceCount = configuration.getInterfaceCount();
                for (int j = 0; j < interfaceCount; j++) {
                    int altSettingsCount = configuration.getAltSettingCount(j);
                    for (int k = 0; k < altSettingsCount; k++) {
                        interfaces[offset++] = configuration.getInterface(j, k);
                    }
                }
            }
        }
        return interfaces;
    }

    /**
     * Returns the number of {@link UsbInterface}s this device contains. For devices with multiple configurations,
     * you will probably want to use {@link UsbConfiguration#getInterfaceCount} instead.
     *
     * @return the number of interfaces
     */
    public int getInterfaceCount() {
        return getInterfaceList().length;
    }

    /**
     * Returns the {@link UsbInterface} at the given index. For devices with multiple configurations, you will
     * probably want to use {@link UsbConfiguration#getInterface} instead.
     *
     * @return the interface
     */
    @NotNull
    public UsbInterface getInterface(int index) {
        return getInterfaceList()[index];
    }

    public int getMaxPacketSizeForMicroFrame(UsbInterface iface, UsbEndpoint ep) {
        return nativeGetMaxAltPacketSize(getNativeObject(), iface.getId(),
                iface.getAlternateSetting(), ep.getAddress());
    }
    private native int nativeGetMaxAltPacketSize(long device, int ifNumber, int ifAlt,
                                                 int address);

    @Override
    public boolean equals(Object o) {
        if (o instanceof UsbDevice) {
            return ((UsbDevice) o).name.equals(name);
        } else {
            return (o instanceof String && o.equals(name));
        }
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("UsbDevice[name=" + name +
                                                  ",vendorId=" + vendorId + ",productId=" + productId +
                                                  ",deviceClass=" + deviceClass + ",subclass=" + subclass
                                                  + ",protocol=" + protocol +
                                                  ",manufacturerName=" + manufacturerName + ",productName="
                                                  + productName
                                                  +
                                                  ",version=" + version + ",serialNumber=" + serialNumber
                                                  + ",configurations=[");
        if (configurations != null) {
            for (UsbConfiguration configuration : configurations) {
                builder.append("\n");
                builder.append(configuration.toString());
            }
        }
        builder.append("]");
        return builder.toString();
    }

    /**
     * Retrieves the {@link ByteBuffer} pointing to a {@code libusb_device_handle} instance in native.
     *
     * @return The {@link ByteBuffer} pointing to a {@code libusb_device_handle} instance in native.
     */
    public long getNativeObject() {
        if (nativeObject == 0) {
            throw new IllegalStateException("This UsbDevice was already closed");
        }
        return nativeObject;
    }

    /**
     * Retrieves a string descriptor from the device.
     *
     * @param device {@link ByteBuffer} pointing to a {@code libusb_device_handle} instance in native. Provided by
     *               {@link UsbDevice#getNativeObject()}.
     * @param index  {@code int} The string index to retrieve. A value of 0 will  cause {@code null} to be returned.
     *
     * @return {@link String} The descriptor or null if one is not present on the device.
     */
    @Nullable
    static native String nativeGetStringDescriptor(long device, int index);

    /**
     * Creates a {@code libusb_device_handle} native instance for the give file descriptor. On Android This file
     * descriptor must be provided by {@code UsbDeviceConnection#getFileDescriptor()} in
     * order to have proper permissions.
     *
     * @param context {@link ByteBuffer} pointing to a {@code libusb_context} instance in native.
     * @param fd      {@code int} The file descriptor for the opened device.
     *
     * @return {@link ByteBuffer} pointing to a {@code libusb_device_handle} instance in native, or {@code null} if a
     * failure occurred.
     */
    private static native long wrapDevice(long context, int fd);

    /**
     * Retrieves the manufacturer name string from the device.
     *
     * @param device     {@link ByteBuffer} pointing to a {@code libusb_device_handle} instance in native. Provided
     *                   by {@link UsbDevice#getNativeObject()}.
     * @param descriptor {@link ByteBuffer} pointing to a {@code libusb_device_descriptor} instanace in native.
     *                   Provided by {@link LibUsbDeviceDescriptor#getNativeObject()}.
     *
     * @return {@link String} The device manufacturer name.
     */
    private native String nativeGetManufacturerString(long device, long descriptor);

    /**
     * Retrieves the serial number string from the device.
     *
     * @param device     {@link ByteBuffer} pointing to a {@code libusb_device_handle} instance in native. Provided
     *                   by {@link UsbDevice#getNativeObject()}.
     * @param descriptor {@link ByteBuffer} pointing to a {@code libusb_device_descriptor} instanace in native.
     *                   Provided by {@link LibUsbDeviceDescriptor#getNativeObject()}.
     *
     * @return {@link String} The device serial number string.
     */
    private native String nativeGetSerialString(long device, long descriptor);

    /**
     * Retrieves the product name string from the device.
     *
     * @param device     {@link ByteBuffer} pointing to a {@code libusb_device_handle} instance in native. Provided
     *                   by {@link UsbDevice#getNativeObject()}.
     * @param descriptor {@link ByteBuffer} pointing to a {@code libusb_device_descriptor} instanace in native.
     *                   Provided by {@link LibUsbDeviceDescriptor#getNativeObject()}.
     *
     * @return {@link String} The device product name.
     */
    private native String nativeGetProductNameString(long device, long descriptor);

    /**
     * Retrieves the product version number for the device.
     *
     * @param descriptor {@link ByteBuffer} pointing to a {@code libusb_device_descriptor} instanace in native.
     *                   Provided by {@link LibUsbDeviceDescriptor#getNativeObject()}.
     *
     * @return {@link String} The device product version.
     */
    private native String nativeGetDeviceVersion(long descriptor);

    /**
     * Retrieves the connection speed for the device.
     *
     * @param device     {@link ByteBuffer} pointing to a {@code libusb_device_handle} instance in native. Provided
     *                   by {@link UsbDevice#getNativeObject()}.
     * @param descriptor {@link ByteBuffer} pointing to a {@code libusb_device_descriptor} instanace in native.
     *                                     Provided by {@link LibUsbDeviceDescriptor#getNativeObject()}.
     *
     * @return
     */
    private native int nativeGetDeviceSpeed(long device, long descriptor);

    /**
     * Retrieves the number of configurations available on the device.
     *
     * @param device {@link ByteBuffer} pointing to a {@code libusb_device_handle} instance in native. Provided by
     *               {@link UsbDevice#getNativeObject()}.
     *
     * @return {@code int} The number of configurations.
     */
    private native int nativeGetConfigurationCount(long device);


    /**
     * Releases all system resources related to the device. Once the object is closed it cannot be used again. The
     * client must register the device with {@link UsbManager} again to retrieve a new instance to reestablish
     * communication with the device.
     */
    public void close() {
        if (nativeObject == 0)
             return;
        synchronized (manager.lock) {
            manager.onClosingDevice();
            nativeClose(getNativeObject());
            manager.onDeviceClosed();
        }
        nativeObject = 0;
        connection.close();
    }

    @Override
    protected void finalize() throws Throwable {
        close();
        super.finalize();
    }

    /**
     * Returns the raw USB descriptors for the device. This can be used to access descriptors not supported directly
     * via the higher level APIs.
     *
     * @return raw USB descriptors
     */
    public byte[] getRawDescriptors() {
        if (nativeObject == 0) {
            throw new IllegalStateException("This UsbDevice was already closed");
        }
        return connection.getRawDescriptors();
    }

    // ====== CONNECTION API ======

    // TODO: libusb_get_port_numbers
    // TODO: libusb_get_container_id_descriptor
    // TODO: libusb_get_bos_descriptor
    // TODO: libusb_alloc/free_streams with libusb_ss_endpoint_companion_descriptor

    /**
     * Clears the stall condition on the provided {@link UsbEndpoint}.
     *
     * @param endpoint The {@link UsbEndpoint} which should be cleared.
     * @return {@link LibusbError} The libusb result.
     */
    public LibusbError clearStall(@NotNull UsbEndpoint endpoint) {
        return LibusbError.fromNative(nativeClearStall(getNativeObject(), endpoint.getAddress()));
    }

    /**
     * Claims exclusive access to a {@link UsbInterface}. This must be done before sending or
     * receiving data on any {@link UsbEndpoint}s belonging to the interface.<p>
     *
     * The alt setting of the provided interface will NOT be applied.
     *
     * @param intf  the interface to claim
     *
     * @return {@link LibusbError} The libusb result.
     */
    public LibusbError claimInterfaceWithoutAltSetting(UsbInterface intf) {
        return LibusbError.fromNative(nativeClaimInterface(getNativeObject(), intf.getId()));
    }

    /**
     * Claims exclusive access to a {@link UsbInterface}. This must be done before sending or
     * receiving data on any {@link UsbEndpoint}s belonging to the interface.<p>
     *
     * The alt setting of the provided interface will be applied.
     *
     * @param intf  the interface to claim
     *
     * @return {@link LibusbError} The libusb result.
     */
    public LibusbError claimInterface(UsbInterface intf) {
        LibusbError ret = claimInterfaceWithoutAltSetting(intf);
        if (ret != LibusbError.LIBUSB_SUCCESS) {
            return ret;
        }
        // Ensure the correct alt setting is set
        return setInterface(intf);
    }

    /**
     * Claims exclusive access to a {@link UsbInterface}. This must be done before sending or
     * receiving data on any {@link UsbEndpoint}s belonging to the interface.<p>
     *
     * The alt setting of the provided interface will be applied.
     *
     * @param intf  the interface to claim
     * @param force true to disconnect kernel driver if necessary
     *
     * @return {@link LibusbError} The libusb result.
     */
    public LibusbError claimInterface(UsbInterface intf, boolean force) {
        LibusbError ret = claimInterface(intf);
        if (ret == LibusbError.LIBUSB_ERROR_BUSY && force) {
            detachKernelDriver(intf);
            return claimInterface(intf);
        }
        return ret;
    }

    /**
     * Claims exclusive access to a {@link UsbInterface}. This must be done before sending or
     * receiving data on any {@link UsbEndpoint}s belonging to the interface.<p>
     *
     * The alt setting of the provided interface will be applied.<p>
     *
     * This function also ensures to set the USB device's configuration in a race-free way. Setting
     * the configuration to the current one will perform a light reset, which is usually undesired,
     * but reading the configuration and then comparing it has time-of-check time-of-use issues,
     * because another program might have changed the configuration in the meantime. The race-free
     * way is to double-check the configuration after claiming the interface and releasing it if
     * the configuration is the wrong one. This function implements this pattern, so after it
     * returns with {@link LibusbError#LIBUSB_SUCCESS}, the configuration is set correctly and the
     * interface is claimed.
     *
     * @param config the configuration to enable
     * @param intf   the interface to claim
     * @param force  true to disconnect kernel driver if necessary
     *
     * @return {@link LibusbError} The libusb result.
     */
    public LibusbError claimInterfaceOnConfiguration(UsbConfiguration config, UsbInterface intf, boolean force) {
        while (true) {
            int curConfig = getConfiguration();
            if (curConfig < 0) {
                return LibusbError.fromNative(curConfig);
            }
            if (config.getId() != curConfig) {
                LibusbError ret = setConfiguration(config);
                if (ret != LibusbError.LIBUSB_SUCCESS) {
                    return ret;
                }
            }
            LibusbError ret = claimInterface(intf, force);
            if (ret != LibusbError.LIBUSB_SUCCESS) {
                return ret;
            }
            curConfig = getConfiguration();
            if (curConfig < 0) {
                return LibusbError.fromNative(curConfig);
            }
            if (curConfig == config.getId()) {
                return LibusbError.LIBUSB_SUCCESS;
            }
            ret = releaseInterface(intf, false);
            if (ret != LibusbError.LIBUSB_SUCCESS) {
                return ret;
            }
        }
    }

    /**
     * Releases exclusive access to a {@link UsbInterface}.
     *
     * @return {@link LibusbError} The libusb result.
     */
    public LibusbError releaseInterface(UsbInterface intf) {
        return LibusbError.fromNative(nativeReleaseInterface(getNativeObject(), intf.getId()));
    }

    /**
     * Releases exclusive access to a {@link UsbInterface}.
     *
     * @param force true to reconnect kernel driver
     * @return {@link LibusbError} The libusb result.
     */
    public LibusbError releaseInterface(UsbInterface intf, boolean force) {
        LibusbError ret = releaseInterface(intf);
        if ((ret == LibusbError.LIBUSB_SUCCESS || ret == LibusbError.LIBUSB_ERROR_NOT_FOUND) && force) {
            return attachKernelDriver(intf);
        }
        return ret;
    }

    /**
     * Attaches a kernel driver to {@link UsbInterface}. Interface must not be claimed.
     *
     * @return {@link LibusbError} The libusb result.
     */
    public LibusbError attachKernelDriver(UsbInterface intf) {
        return LibusbError.fromNative(nativeAttachKernelDriver(getNativeObject(), intf.getId()));
    }

    /**
     * Detaches a kernel driver to {@link UsbInterface}.
     *
     * @return {@link LibusbError} The libusb result.
     */
    public LibusbError detachKernelDriver(UsbInterface intf) {
        return LibusbError.fromNative(nativeDetachKernelDriver(getNativeObject(), intf.getId()));
    }

    /**
     * Returns whether a kernel driver is attached to this interface.<p>
     *
     * Note that this function should not be used when claiming an interface as it is prone to
     * time-to-check time-to-use races. The kernel driver might attach/detach immediately after the
     * function returned. If trying to claim an interface, simply try to detach the kernel driver
     * after {@link LibusbError#LIBUSB_ERROR_BUSY} is returned from {@link
     * #claimInterface(UsbInterface)}, or use {@link #claimInterface(UsbInterface, boolean)} which
     * implements this pattern in a simple wrapper.
     *
     * @return 0 if no kernel driver is attached, 1 if kernel driver is attached, or negative {@link
     * LibusbError} on error.
     */
    public int hasAttachedKernelDriver(UsbInterface intf) {
        return nativeHasKernelDriver(getNativeObject(), intf.getId());
    }

    /**
     * Sets the current {@link UsbInterface}. Used to select between two interfaces with the same ID but different
     * alternate setting. The interface should be claimed before changing the alternate setting.
     *
     * @return {@link LibusbError} The libusb result.
     */
    public LibusbError setInterface(UsbInterface intf) {
        return LibusbError.fromNative(nativeSetInterface(getNativeObject(), intf.getId(),
                intf.getAlternateSetting()));
    }

    /**
     * Gets the device's current {@link UsbConfiguration}'s ID.
     *
     * @return If positive or zero, the configuration number. If negative, an {@link LibusbError}.
     */
    public int getConfiguration() {
        return nativeGetConfiguration(getNativeObject());
    }

    /**
     * Sets the device's current {@link UsbConfiguration}.
     *
     * @return {@link LibusbError} The libusb result.
     */
    public LibusbError setConfiguration(UsbConfiguration configuration) {
        return LibusbError.fromNative(nativeSetConfiguration(getNativeObject(), configuration.getId()));
    }

    /**
     * Performs a control transaction on endpoint zero for this device. The direction of the transfer is determined
     * by the request type. If requestType & {@link UsbConstants#USB_ENDPOINT_DIR_MASK} is
     * {@link UsbConstants#USB_DIR_OUT}, then the transfer is a write, and if it is {@link UsbConstants#USB_DIR_IN},
     * then the transfer is a read.
     * <p>
     * This method transfers data starting from index 0 in the buffer. To specify a different offset, use
     * {@link #controlTransfer(int, int, int, int, byte[], int, int, int)}.
     * </p>
     *
     * @param requestType request type for this transaction
     * @param request     request ID for this transaction
     * @param value       value field for this transaction
     * @param index       index field for this transaction
     * @param buffer      buffer for data portion of transaction,
     *                    or null if no data needs to be sent or received
     * @param length      the length of the data to send or receive
     * @param timeout     in milliseconds
     *
     * @return length of data transferred (or zero) for success, or negative value for failure
     */
    public int controlTransfer(int requestType, int request, int value, int index, byte[] buffer, int length,
                               int timeout) {
        return controlTransfer(requestType, request, value, index, buffer, 0, length, timeout);
    }

    /**
     * Performs a control transaction on endpoint zero for this device. The direction of the transfer is determined
     * by the request type. If requestType & {@link UsbConstants#USB_ENDPOINT_DIR_MASK} is
     * {@link UsbConstants#USB_DIR_OUT}, then the transfer is a write, and if it is {@link UsbConstants#USB_DIR_IN},
     * then the transfer is a read.
     *
     * @param requestType request type for this transaction
     * @param request     request ID for this transaction
     * @param value       value field for this transaction
     * @param index       index field for this transaction
     * @param buffer      buffer for data portion of transaction,
     *                    or null if no data needs to be sent or received
     * @param offset      the index of the first byte in the buffer to send or receive
     * @param length      the length of the data to send or receive
     * @param timeout     in milliseconds
     *
     * @return length of data transferred (or zero) for success, or negative value for failure
     */
    public int controlTransfer(int requestType, int request, int value, int index, byte[] buffer, int offset,
                               int length, int timeout) {
        checkBounds(buffer, offset, length);
        return nativeControlRequest(getNativeObject(), requestType, request, value, index, buffer, offset,
                length, timeout);
    }

    /**
     * Performs a bulk transaction on the given endpoint. The direction of the transfer is determined by the
     * direction of the endpoint.
     * <p>
     * This method transfers data starting from index 0 in the buffer. To specify a different offset, use
     * {@link #bulkTransfer(UsbEndpoint, byte[], int, int, int)}.
     * </p>
     *
     * @param endpoint the endpoint for this transaction
     * @param buffer   buffer for data to send or receive; can be {@code null} to wait for next
     *                 transaction without reading data
     * @param length   the length of the data to send or receive
     * @param timeout  in milliseconds, 0 is infinite
     *
     * @return length of data transferred (or zero) for success, or negative value for failure
     */
    public int bulkTransfer(UsbEndpoint endpoint, byte[] buffer, int length, int timeout) {
        return bulkTransfer(endpoint, buffer, 0, length, timeout);
    }

    /**
     * Performs a bulk transaction on the given endpoint. The direction of the transfer is determined by the
     * direction of the endpoint.
     *
     * @param endpoint the endpoint for this transaction
     * @param buffer   buffer for data to send or receive
     * @param offset   the index of the first byte in the buffer to send or receive
     * @param length   the length of the data to send or receive
     * @param timeout  in milliseconds, 0 is infinite
     *
     * @return length of data transferred (or zero) for success, or negative value for failure
     */
    public int bulkTransfer(UsbEndpoint endpoint, byte[] buffer, int offset, int length, int timeout) {
        checkBounds(buffer, offset, length);
        return nativeBulkRequest(getNativeObject(), endpoint.getAddress(), buffer, offset, length, timeout);
    }

    /**
     * Performs an interrupt transaction on the given endpoint. The direction of the transfer is determined by the
     * direction of the endpoint.
     * <p>
     * This method transfers data starting from index 0 in the buffer. To specify a different offset, use
     * {@link #interruptTransfer(UsbEndpoint, byte[], int, int, int)}.
     * </p>
     *
     * @param endpoint the endpoint for this transaction
     * @param buffer   buffer for data to send or receive; can be {@code null} to wait for next
     *                 transaction without reading data
     * @param length   the length of the data to send or receive
     * @param timeout  in milliseconds, 0 is infinite
     *
     * @return length of data transferred (or zero) for success, or negative value for failure
     */
    public int interruptTransfer(UsbEndpoint endpoint, byte[] buffer, int length, int timeout) {
        return interruptTransfer(endpoint, buffer, 0, length, timeout);
    }

    /**
     * Performs an interrupt transaction on the given endpoint. The direction of the transfer is determined by the
     * direction of the endpoint.
     *
     * @param endpoint the endpoint for this transaction
     * @param buffer   buffer for data to send or receive
     * @param offset   the index of the first byte in the buffer to send or receive
     * @param length   the length of the data to send or receive
     * @param timeout  in milliseconds, 0 is infinite
     *
     * @return length of data transferred (or zero) for success, or negative value for failure
     */
    public int interruptTransfer(UsbEndpoint endpoint, byte[] buffer, int offset, int length, int timeout) {
        checkBounds(buffer, offset, length);
        return nativeInterruptRequest(getNativeObject(), endpoint.getAddress(), buffer, offset, length, timeout);
    }

    /**
     * Performs an asynchronous transaction. The direction of the transfer is determined differently
     * depending on the type of the transfer:<p>
     * - control transfer: the direction is read from the request type in the setup packet that is
     *                     in the first 8 bytes of the buffer<p>
     * - interrupt / bulk / isochronous transfer: the direction is determined by the endpoint's
     *                                            direction.<p>
     *
     * In order to continuously transfer data, for example to an isochronous endpoint, make sure to
     * queue multiple transfers on the endpoint at the same time, and re-queue a transfer in the
     * completion callback. This prevents scheduling delays from causing transfer interruptions.
     * For multiple queued transfers on one endpoint, you are guaranteed to receive callbacks in the
     * same order as the transfers were submitted, except if a transfer is manually canceled.
     *
     * @param transfer the transfer, including type, endpoint, timeout, buffer and callback. this
     *                 metadata can  later be used to cancel the transfer using {@link
     *                 #cancelAsyncTransfer(AsyncTransfer)}
     *
     * @return error code or {@link LibusbError#LIBUSB_SUCCESS} if success
     */
    // wrt order: https://github.com/libusb/libusb/issues/1077 - Linux backend is OK because it uses
    // reap urb ioctl which reads from an ordered list in kernel.
    public LibusbError asyncTransfer(@NotNull AsyncTransfer transfer) {
        if (transfer.getCallback() == null) {
            throw new IllegalArgumentException("Transfer callback should be set");
        }
        @NonNull ByteBuffer buffer = transfer.getBuffer();
        return LibusbError.fromNative(nativeRequestAsync(getNativeObject(), transfer,
                buffer, buffer.position(), buffer.remaining()));
    }

    /**
     * Cancel an asynchronous transaction. The error callback of the transfer will be called once
     * the cancellation is done.
     */
    public LibusbError cancelAsyncTransfer(@NonNull AsyncTransfer transfer) {
        return LibusbError.fromNative(nativeCancelAsync(transfer.getNativeObject()));
    }

    /**
     * Reset USB port for the connected device.
     *
     * @return {@link LibusbError} The libusb result.
     */
    public LibusbError resetDevice() {
        return LibusbError.fromNative(nativeResetDevice(getNativeObject()));
    }

    private static void checkBounds(byte[] buffer, int start, int length) {
        final int bufferLength = (buffer != null ? buffer.length : 0);
        if (length < 0 || start < 0 || start + length > bufferLength) {
            throw new IllegalArgumentException("Buffer start or length out of bounds.");
        }
    }

    private static native boolean nativeInitialize();

    private native void nativeClose(long device);

    private native int nativeClearStall(long device, int address);

    private native int nativeClaimInterface(long device, int interfaceID);

    private native int nativeReleaseInterface(long device, int interfaceID);

    private native int nativeHasKernelDriver(long device, int interfaceID);

    private native int nativeAttachKernelDriver(long device, int interfaceID);

    private native int nativeDetachKernelDriver(long device, int interfaceID);

    private native int nativeSetInterface(long device, int interfaceID, int alternateSetting);

    private native int nativeGetConfiguration(long device);

    private native int nativeSetConfiguration(long device, int configurationID);

    private native int nativeControlRequest(long device, int requestType, int request, int value,
                                            int index, byte[] buffer, int offset, int length, int timeout);

    private native int nativeRequestAsync(long device, @NotNull AsyncTransfer transfer,
                                          @NotNull ByteBuffer buffer, int offset, int length);

    private native int nativeCancelAsync(long transfer);

    private native int nativeBulkRequest(long device, int endpoint, byte[] buffer, int offset,
                                         int length, int timeout);

    private native int nativeInterruptRequest(long device, int endpoint, byte[] buffer, int offset,
                                              int length, int timeout);

    private native int nativeResetDevice(long device);

}
