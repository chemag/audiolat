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
  private long last_midi_timestamp = -1;

  public void javaMidiSignal(long nanotime) {
      midi_timestamp = nanotime;
  }

  public void runJavaAudio(final Context context, final TestSettings settings) {
    Log.d(LOG_ID, "Start java experiment");
    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
    final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    int recordBufferSizeInBytes_ = AudioRecord.getMinBufferSize(
            settings.sampleRate, AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT);
    int playbackBufferSizeInBytes_ = AudioTrack.getMinBufferSize(
            settings.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT);

    if (settings.playoutBufferSizeInBytes > 0) {
      Log.d(LOG_ID, "Playback min buffer size is " + playbackBufferSizeInBytes_  +
              " change to:"+settings.playoutBufferSizeInBytes );
      playbackBufferSizeInBytes_ = settings.playoutBufferSizeInBytes;
    }

    Log.e(LOG_ID, "playback buffer size:" + playbackBufferSizeInBytes_);


    // create player object
    final AudioTrack player =
            new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(settings.usage)
                            .setContentType(settings.contentType)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(settings.sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(playbackBufferSizeInBytes_)
                    .setPerformanceMode(settings.javaaudioPerformanceMode)
                    .build();

    if (settings.recordBufferSizeInBytes > 0){
      Log.d(LOG_ID, "Recording min size is " + recordBufferSizeInBytes_  +
                 " change to:"+settings.recordBufferSizeInBytes );
      recordBufferSizeInBytes_ = settings.recordBufferSizeInBytes;

    }
    final ByteBuffer recordData = ByteBuffer.allocateDirect(recordBufferSizeInBytes_ * 4);

    // create record object
    final AudioRecord recorder =
        new AudioRecord.Builder()
            .setAudioSource(settings.inputPreset)
            .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(settings.sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build())
                                .setBufferSizeInBytes(recordBufferSizeInBytes_)
                .build();

    final int recordBufferSizeInBytes = recordBufferSizeInBytes_;
    final int playbackBufferSizeInByte = playbackBufferSizeInBytes_;
    final byte audioData[] = recordData.array();

    // open the record file path
    BufferedOutputStream os = null;
    Log.d(LOG_ID, settings.outputFilePath);
    try {
      os = new BufferedOutputStream(new FileOutputStream(settings.outputFilePath));
    } catch (FileNotFoundException e) {
      Log.e(LOG_ID, "File not found for recording ", e);
      return;
    }
    final BufferedOutputStream fos = os;

    recorder.setNotificationMarkerPosition(settings.sampleRate * settings.timeout);
    recorder.setPositionNotificationPeriod(settings.sampleRate / 2);
    AudioTimestamp ts1 = new AudioTimestamp();
    AudioTimestamp ts2 = new AudioTimestamp();

    // create thread
    Thread rec = new Thread(new Runnable() {
      @Override
      public void run() {
        midi_timestamp = 0;
        int written_frames = 0;
        float last_ts = -settings.timeBetweenSignals;
        int rec_buffer_index = 0;
        int player_offset = 0;
        float time_sec = 0;
        byte[] endSignal = settings.endSignal.array();
        byte[] silence = new byte[playbackBufferSizeInByte];

        recorder.startRecording();
        player.play();
        while (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
          int diff_sec = (int)(time_sec - last_ts);
          if ((settings.timeBetweenSignals > 0 && diff_sec >= settings.timeBetweenSignals) || (midi_timestamp > 0)  || player_offset > 0) {
            // Either we have a midi timestamp or there is sufficient time from last signal
            long nano = System.nanoTime();
            int written = player.write(endSignal, player_offset, endSignal.length - player_offset, AudioTrack.WRITE_NON_BLOCKING);
            if (written > 0) {
              player_offset += written;
            }
            if (written == AudioTrack.ERROR_BAD_VALUE) {
              Log.e(LOG_ID, "Failed to write: offset =  " + player_offset + ", len = "+endSignal.length);
            }
            if (player_offset >= endSignal.length || written == 0) {
              // Written everything, reset and wait for next trigger
              player_offset = 0;
              midi_timestamp = 0;
            }
            if (midi_timestamp > 0) {
              Log.d(LOG_ID,
                  String.format("midi triggered: %d curr time: %d, delay: %d", midi_timestamp, nano,
                      (nano - midi_timestamp)));
            }
          } else {
            player.write(silence, 0, silence.length, AudioTrack.WRITE_NON_BLOCKING);
          }
          int read_bytes = recorder.read(audioData, 0, audioData.length, AudioRecord.READ_NON_BLOCKING);
          if (read_bytes > 0) {
            try {
              if (((settings.timeBetweenSignals > 0)
                      && (diff_sec >= settings.timeBetweenSignals))
                  || (rec_buffer_index > 0)) {
                // An timed event has been triggered, write the dirac start signal
                // signal size in bytes
                int signal_size_in_bytes =
                        (read_bytes > settings.beginSignalSizeInBytes - rec_buffer_index)
                                ? settings.beginSignalSizeInBytes - rec_buffer_index
                                : read_bytes;

                if (rec_buffer_index > 0) {
                  // Write tail
                  fos.write(settings.beginSignal.array(), rec_buffer_index, signal_size_in_bytes);
                  fos.write(audioData, signal_size_in_bytes, read_bytes - signal_size_in_bytes);
                } else {
                  // Write the beginning of the signal, the recorded data to be written could be 0
                  fos.write(audioData, 0, read_bytes - signal_size_in_bytes);
                  fos.write(settings.beginSignal.array(), 0, signal_size_in_bytes);
                }
                rec_buffer_index += signal_size_in_bytes;
                last_ts = time_sec;
              } else {
                fos.write(audioData, 0, read_bytes);
              }
            } catch (IOException e) {
              e.printStackTrace();
            }

            written_frames += read_bytes / 2;
            time_sec = (float) written_frames / (float) settings.sampleRate;

            if (rec_buffer_index >= settings.beginSignalSizeInBytes) {
              rec_buffer_index = 0;
            }

            if (time_sec > settings.timeout) {
              break;
            }

          } else {
            Log.e(LOG_ID, "Error reading audio data!");
          }

          try {
            Thread.sleep(40);
          } catch (InterruptedException e) {
            e.printStackTrace();
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
