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

public class JavaAudio {
  public static final String LOG_ID = "audiolat";
  private long midi_timestamp = -1;
  public void javaMidiSignal(long nanotime) {
    midi_timestamp = nanotime;
  }

  public void runJavaAudio(final Context context, final TestSettings settings) {
    Log.d(LOG_ID, "Start java experiment");

    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
    final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    final int recordBufferSizeInBytes = AudioRecord.getMinBufferSize(
        settings.sampleRate, AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT);
    final int playbackBufferSizeInBytes = AudioTrack.getMinBufferSize(
        settings.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT);
    if (recordBufferSizeInBytes < 0) {
      Log.e(LOG_ID, "buffer_size:" + recordBufferSizeInBytes);
      return;
    }
    final byte audioData[] = new byte[recordBufferSizeInBytes];
    final ByteBuffer recordData = ByteBuffer.allocateDirect(settings.sampleRate * 4);

    // create player object
    final AudioTrack player =
        new AudioTrack.Builder()
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
            .setBufferSizeInBytes(settings.endSignalSizeInBytes)
            .setPerformanceMode(settings.javaaudioPerformanceMode)
            .build();

    // create record object
    int playbackRead = 1;
    final AudioRecord recorder =
        new AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
            .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(settings.sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build())
            .setBufferSizeInBytes(settings.recordBufferSizeInBytes)
            .build();

    // print the recorder latency value
    int latency = ((Integer) recorder.getMetrics().get(AudioRecord.MetricsConstants.LATENCY))
                      .intValue(); // ms
    Log.d(LOG_ID, "input_latency: " + latency);

    // open the record file path
    BufferedOutputStream os = null;
    Log.d(LOG_ID, settings.outputFilePath);
    try {
      os = new BufferedOutputStream(new FileOutputStream(settings.outputFilePath));
    } catch (FileNotFoundException e) {
      Log.e(LOG_ID, "File not found for recording ", e);
    }
    final BufferedOutputStream fos = os;

    // set the recorder position
    recorder.setRecordPositionUpdateListener(new AudioRecord.OnRecordPositionUpdateListener() {
      @Override
      public void onMarkerReached(AudioRecord audioRecord) {
        AudioTimestamp ts = new AudioTimestamp();
        Log.d(LOG_ID,
            "** onMarkerReached: ts: "
                + audioRecord.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC));
      }

      @Override
      public void onPeriodicNotification(AudioRecord audioRecord) {}
    });

    recorder.setNotificationMarkerPosition(settings.sampleRate * settings.timeout);
    recorder.setPositionNotificationPeriod(settings.sampleRate / 2);
    AudioTimestamp ts1 = new AudioTimestamp();
    AudioTimestamp ts2 = new AudioTimestamp();

    // start everything
    Log.d(LOG_ID, String.format("endSignalSizeInBytes: %d", settings.endSignalSizeInBytes));
    player.write(settings.endSignal.array(), 0, settings.endSignalSizeInBytes);

    // create thread
    Thread rec = new Thread(new Runnable() {
      @Override
      public void run() {
        recorder.startRecording();

        int counter = 0;
        int recIndex = 0;
        int written_frames = 0;
        float last_ts = 0;
        int rec_buffer_index = 0;
        while (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
          int read_bytes = recorder.read(audioData, 0, audioData.length);
          float time_sec = (float) written_frames / (float) settings.sampleRate;
          Log.d(LOG_ID,
              String.format("record num_frames: %d time_sec: %.2f last_time: %.2f recbi: %d",
                  written_frames, time_sec, last_ts, rec_buffer_index));

          if (((settings.timeBetweenSignals > 0)
                  && (time_sec - last_ts > settings.timeBetweenSignals))
              || (midi_timestamp > 0)) {
            long nano = System.nanoTime();
            Log.d(LOG_ID, "Start playing signal");
            player.stop();
            player.setPlaybackHeadPosition(0);
            player.play();
            if (midi_timestamp > 0) {
              Log.d(LOG_ID,
                  String.format("midi triggered: %d curr time: %d, delay: %d", midi_timestamp, nano,
                      (nano - midi_timestamp)));

              midi_timestamp = 0;
            }
          }
          if (read_bytes > 0) {
            try {
              if (((settings.timeBetweenSignals > 0)
                      && (time_sec - last_ts > settings.timeBetweenSignals))
                  || (rec_buffer_index > 0)) {
                // signal size in bytes
                int signal_size_in_bytes =
                    (read_bytes > settings.beginSignalSizeInBytes - rec_buffer_index)
                    ? settings.beginSignalSizeInBytes - rec_buffer_index
                    : read_bytes;

                if (rec_buffer_index > 0) {
                  // Write tail
                  Log.d(LOG_ID, "wt arral. " + settings.beginSignal.array().length);
                  fos.write(settings.beginSignal.array(), rec_buffer_index, signal_size_in_bytes);
                  fos.write(audioData, signal_size_in_bytes, read_bytes - signal_size_in_bytes);
                } else {
                  // Write the beginning of the signal, the recorded data to be written could be 0
                  fos.write(audioData, 0, read_bytes - signal_size_in_bytes);
                  Log.d(LOG_ID, "wh arral. " + settings.beginSignal.array().length);
                  fos.write(settings.beginSignal.array(), 0, signal_size_in_bytes);
                }

                rec_buffer_index += signal_size_in_bytes;
                last_ts = time_sec;

                Log.d(
                    LOG_ID, String.format(String.format("rec_buffer_index: %d", rec_buffer_index)));
              } else {
                Log.d(LOG_ID, "Write normal data: " + read_bytes);
                fos.write(audioData, 0, read_bytes);
              }
            } catch (IOException e) {
              e.printStackTrace();
            }

            written_frames += read_bytes / 2;
            if (rec_buffer_index >= settings.beginSignalSizeInBytes) {
              rec_buffer_index = 0;
            }

            if (time_sec > settings.timeout) {
              break;
            }

          } else {
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
    Log.d(LOG_ID,
        "res: " + res + " ts1: " + (ts1.framePosition) + " ts2: " + (ts2.framePosition)
            + " diff: " + (float) (ts2.nanoTime - ts1.nanoTime) / 1000000);

    // stop both recorder and player
    recorder.stop();
    Trace.endSection();

    recorder.release();
    player.stop();
    Trace.endSection();
    player.release();
  }
}
