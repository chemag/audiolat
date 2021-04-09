package com.facebook.audiolat;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.util.HashMap;
import java.util.Vector;

public class UsbMidi {
    static String id = "usbmidi";
    UsbDevice mDevice = null;
    boolean mRunning = true;
    MainActivity mMain;
    private static final String ACTION_USB_PERMISSION =
            "com.android.example.USB_PERMISSION";

    public UsbMidi(MainActivity main) {
        mMain = main;
    }

    public void openDevice(UsbDevice device, Context mContext){
        String name = device.getDeviceName();
        String product = device.getProductName();
        String serial = device.getSerialNumber();
        mDevice = device;

        Log.d(id, "Open midi device");
        int interfaceCount = mDevice.getInterfaceCount();
        for (int index = 0; index < interfaceCount;index++) {
            UsbInterface interf = mDevice.getInterface(index);
            int endpointcount = interf.getEndpointCount();
            Log.d(id, "Endpoint count = "+endpointcount);
            for (int eindex = 0; eindex < endpointcount; eindex++) {
                UsbEndpoint ep = interf.getEndpoint(eindex);
                Log.d(id, "End point address: " + ep.getAddress());
                Log.d(id, "Direction: " + ep.getDirection());
                Log.d(id, "End point type: " + ep.getType());
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    Log.d(id, "We have an input");
                    int TIMEOUT = 0;
                    boolean forceClaim = true;
                    UsbManager manager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
                    final UsbEndpoint endpoint = ep;
                    final UsbDeviceConnection connection = manager.openDevice(device);
                    connection.claimInterface(interf, forceClaim);
                    (new Thread(new Runnable() {
                        @Override
                        public void run() {
                            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                            final int maxPacketSize = endpoint.getMaxPacketSize();
                            final byte[] bulkReadBuffer = new byte[maxPacketSize];
                            final byte[] readBuffer = new byte[maxPacketSize * 2]; // *2 for safety (BUFFER_LENGTH+4 would be enough)
                            int readBufferSize = 0;
                            long lastTimeStamp = 0;
                            final byte[] read = new byte[maxPacketSize * 2];

                            while (mRunning) {
                                int length = connection.bulkTransfer(endpoint, bulkReadBuffer, maxPacketSize, 10);

                                if (length <= 0) {
                                    continue;
                                }

                                System.arraycopy(bulkReadBuffer, 0, readBuffer, readBufferSize, length);
                                readBufferSize += length;

                                if (readBufferSize < 4) {
                                    // more data needed
                                    continue;
                                }

                                // USB MIDI data stream: 4 bytes boundary
                                int readSize = readBufferSize / 4 * 4;
                                System.arraycopy(readBuffer, 0, read, 0, readSize); // fill the read array

                                // keep unread bytes
                                int unreadSize = readBufferSize - readSize;
                                if (unreadSize > 0) {
                                    System.arraycopy(readBuffer, readSize, readBuffer, 0, unreadSize);
                                    readBufferSize = unreadSize;
                                } else {
                                    readBufferSize = 0;
                                }

                                for (int i = 0; i < readSize; i += 4) {
                                    int channel = (read[i] >> 4) & 0xf;
                                    int event = read[i] & 0xf;
                                    int byte1 = read[i + 1] & 0xff;
                                    int byte2 = read[i + 2] & 0xff;
                                    int byte3 = read[i + 3] & 0xff;

                                    switch (event) {
                                        case 9:
                                            if (byte3 == 0x00) {
                                                long timestamp = System.nanoTime();
                                                if (timestamp - lastTimeStamp / 1000000 > 1000) {
                                                    mMain.triggerMidi(timestamp);
                                                }
                                            } else {
                                                Log.d(id, "midi on");
                                            }

                                            break;
                                    }

                                }
                            }
                            connection.close();

                        }
                    })).start();
                }
            }


        }



    }

    public void stop() {
        mRunning = false;
    }
}
