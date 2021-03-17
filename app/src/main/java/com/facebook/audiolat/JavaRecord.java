package com.facebook.audiolat;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Trace;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.nio.ByteBuffer;

public class JavaRecord {
    public static final String LOG_ID = "audiolat";

    public void record(final Context context, final TestSettings settings) {
        Log.d(LOG_ID, "Start java record");

        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        final int AUDIO_SOURCE = MediaRecorder.AudioSource.DEFAULT;
        final int CHANNEL_IN_CONFIG = AudioFormat.CHANNEL_IN_MONO;
        final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int BUFFER_SIZE = AudioRecord.getMinBufferSize(settings.sampleRate, CHANNEL_IN_CONFIG, AUDIO_FORMAT);
        final int playbackBufferSize = AudioTrack.getMinBufferSize(settings.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT);
        if (BUFFER_SIZE < 0) {
            Log.e(LOG_ID, "Buffer size is:" + BUFFER_SIZE);
            return;
        }
        final byte audioData[] = new byte[BUFFER_SIZE];
        final ByteBuffer recordData = ByteBuffer.allocateDirect(settings.sampleRate * 4);


        final AudioTrack player = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(settings.usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(settings.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(settings.endSignalSize)
                .build();

        int playbackRead = 1;
        final AudioRecord recorder = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(settings.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build())
                .setBufferSizeInBytes(2 * settings.recordBufferSize)
                .build();

        int latency = ((Integer)recorder.getMetrics().get(AudioRecord.MetricsConstants.LATENCY)).intValue(); //ms
        Log.d(LOG_ID, "Input latency: " + latency);
        BufferedOutputStream os = null;
        Log.d(LOG_ID, settings.outputFilePath);
        try {
            os = new BufferedOutputStream(new FileOutputStream(settings.outputFilePath));
        } catch (FileNotFoundException e) {
            Log.e(LOG_ID, "File not found for recording ", e);
        }
        final BufferedOutputStream fos = os;

        recorder.setRecordPositionUpdateListener(new AudioRecord.OnRecordPositionUpdateListener() {
            @Override
            public void onMarkerReached(AudioRecord audioRecord) {
                AudioTimestamp ts = new AudioTimestamp();
                Log.d(LOG_ID, "** onMarkerReached: ts = " + audioRecord.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC));
            }

            @Override
            public void onPeriodicNotification(AudioRecord audioRecord) {
                //  AudioTimestamp ts = new AudioTimestamp();
                //   Log.d(LOG_ID, "onPeriodicNotification: " + audioRecord.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC));
            }
        });

        recorder.setNotificationMarkerPosition(settings.sampleRate * settings.timeout);
        recorder.setPositionNotificationPeriod(settings.sampleRate / 2);
        AudioTimestamp ts1 = new AudioTimestamp();
        AudioTimestamp ts2 = new AudioTimestamp();

        //Start everything
        Log.d(LOG_ID, String.format("length: %d", settings.endSignalSize * 2));
        player.write(settings.endSignal.array(), 0, settings.endSignalSize * 2);
        Thread rec = new Thread(new Runnable() {
            @Override
            public void run() {
                //    try {

                recorder.startRecording();

                int counter = 0;
                int recIndex = 0;
                int written_frames = 0;
                float last_ts = 0;
                int rec_buffer_index = 0;
                while (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {

                    int read = recorder.read(audioData, 0, audioData.length);
                    float time_sec = (float)written_frames / (float)settings.sampleRate;
                    Log.d(LOG_ID, String.format("record num_frames: %d time_sec: %.2f, last time = %.2f, recbi = %d", written_frames, time_sec, last_ts, rec_buffer_index));
                    if (read > 0) {
                        try {
                            if (time_sec - last_ts > 2 || rec_buffer_index > 0) {
                                //signal_size in bytes
                                int signal_size = (read > settings.beginSignalSize * 2 - rec_buffer_index) ?
                                        settings.beginSignalSize * 2 - rec_buffer_index : read;

                                if (rec_buffer_index > 0) {
                                    //Write tail
                                    Log.d(LOG_ID, "wt arral. "+settings.beginSignal.array().length);
                                    fos.write(settings.beginSignal.array(), rec_buffer_index, signal_size);
                                    fos.write(audioData, signal_size, read - signal_size);
                                } else {
                                    //Write the beginning of the signal, the recorded data to be written could be 0
                                    fos.write(audioData, 0, read - signal_size);
                                    Log.d(LOG_ID, "wh arral. "+settings.beginSignal.array().length);
                                    fos.write(settings.beginSignal.array(), 0, signal_size);

                                }

                                // currentData.put(audioData, recIndex, read);
                                //player.write(buffer, 0, buffer.length);
                                if (rec_buffer_index == 0) {

                                    Log.d(LOG_ID, "Start playing signal");
                                    player.stop();
                                    player.setPlaybackHeadPosition(0);
                                    player.play();
                                }
                                rec_buffer_index += signal_size;
                                last_ts = time_sec;

                                Log.d(LOG_ID, String.format(String.format("rec_buffer_index = %d", rec_buffer_index)));
                            } else {
                                Log.d(LOG_ID, "Write normal data: " + read);
                                fos.write(audioData, 0, read);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                        written_frames += read / 2;
                        if (rec_buffer_index >= settings.beginSignalSize) {
                            rec_buffer_index = 0;
                        }

                        if (time_sec > settings.timeout) {
                            break;
                        }

                    }  else {
                        Log.e(LOG_ID, "Error reading audio data!");

                    }
                }
            }
        });


        rec.start();

        try {
            rec.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        int res = recorder.getTimestamp(ts2, AudioTimestamp.TIMEBASE_MONOTONIC);
        Log.d(LOG_ID, "res = " + res + ", " + (ts1.framePosition) + ", " + (ts2.framePosition) + ", " + (float) (ts2.nanoTime - ts1.nanoTime)/1000000);

        recorder.stop();
        Trace.endSection();

        recorder.release();
        player.stop();
        Trace.endSection();
        player.release();





    }
}


