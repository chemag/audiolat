package com.facebook.audiolat;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class utils {
    public static final String LOG_ID = "audiolat";
    public static final double MAX = Math.pow(2, 15) - 1;

    public static double dBToFloat(double val) {
        return Math.pow(10, val / 20.0);
    }


    public static double floatToDB(double val) {
        if (val <=0)
            return -100;
        return 20 * Math.log10(val);
    }


    public static void convertBytesToFloats(byte[] input, float[] output, boolean little_endian) {

        short[] mShorts = new short[output.length];

        if (little_endian) {
            ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(mShorts);
        } else {
            ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN).asShortBuffer().get(mShorts);
        }
        int index = 0;
        for (short s : mShorts) {
            output[index++] = (float)(s / MAX);
        }
    }

    public static void convertFloatesToBytes(float[] input, byte[] output, boolean little_endian) {
        short[] mShorts = new short[output.length];
        if (mShorts == null || input.length != mShorts.length) {
            mShorts = new short[input.length];
        }

        int index = 0;
        for (float f : input) {
            mShorts[index++] = (short) (f * MAX);
        }


        if (little_endian) {
            ByteBuffer buffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN);
            buffer.asShortBuffer().put(mShorts);
        } else {
            ByteBuffer buffer = ByteBuffer.wrap(output).order(ByteOrder.BIG_ENDIAN);
            buffer.asShortBuffer().put(mShorts);
        }


    }

    public static void gain(byte[] input, float gaindB) {
        if (gaindB > 6) {
            Log.d(LOG_ID, "No crazy distortion please");
            return;
        }
        double mult = dBToFloat(gaindB);
        float[] data = new float[(int) (input.length / 2)];
        convertBytesToFloats(input, data, true);
        for (int i = 0; i < data.length; i++) {
            data[i] = (float) (data[i] * mult);
        }

        convertFloatesToBytes(data, input, true);
    }

}
