package com.facebook.latencycheck;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
  public static final String LOG_ID = "latencycheck";

  int mSampelRate = 16000;
  int mTimeout = 15;
  int mRecBufferSize = 32;
  int mPlayBufferSize = 32;
  int mReferenceFile = R.raw.chirp2_16k_300ms;
  int mStartSignal = R.raw.start_signal;

  static {
    System.loadLibrary("latencycheck");
  }

  public native int runAAudio(TestSettings settings);

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    String[] permissions = retrieveNotGrantedPermissions(this);

    if (permissions != null && permissions.length > 0) {
      int REQUEST_ALL_PERMISSIONS = 0x4562;
      ActivityCompat.requestPermissions(this, permissions, REQUEST_ALL_PERMISSIONS);
    }

    setContentView(R.layout.activity_main);
    String file_path = "/sdcard/lc_capture";

    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

    try {
      Bundle extras = this.getIntent().getExtras();

      if (extras != null) {
        if (extras.containsKey("sr")) {
          String rate = extras.getString("sr");
          mSampelRate = Integer.parseInt(rate);
        }
        if (extras.containsKey("t")) {
          String timeout = extras.getString("t");
          mTimeout = Integer.parseInt(timeout);
        }
        if (extras.containsKey("rbs")) {
          String timeout = extras.getString("rbs");
          mRecBufferSize = Integer.parseInt(timeout);
        }
        if (extras.containsKey("pbs")) {
          String timeout = extras.getString("pbs");
          mPlayBufferSize = Integer.parseInt(timeout);
        }
      }
      switch (mSampelRate) {
        case 48000:
          mReferenceFile = R.raw.chirp2_48k_300ms;
          file_path += "_chirp2_48k_300ms.raw";
          break;
        case 16000:
          mReferenceFile = R.raw.chirp2_16k_300ms;
          file_path += "_chirp2_16k_300ms.raw";
          break;
        case 8000:
          mReferenceFile = R.raw.chirp2_8k_300ms;
          file_path += "_chirp2_8k_300ms.raw";
          break;
        default:
          Log.d(LOG_ID, "Unsupported sampelrate:" + mSampelRate);
      }

      InputStream is = this.getResources().openRawResource(mReferenceFile);
      final int referenceSize = is.available();
      final byte[] playbackBuffer = new byte[referenceSize];

      int read = is.read(playbackBuffer);
      final ByteBuffer referenceData = ByteBuffer.allocateDirect(read);
      referenceData.put(playbackBuffer); // Small files only :)
      is.close();

      is = this.getResources().openRawResource(mStartSignal);
      final int startsignalSize = is.available();
      final byte[] startSignalBuffer = new byte[startsignalSize];

      read = is.read(startSignalBuffer);
      final ByteBuffer startSignal = ByteBuffer.allocateDirect(read);
      startSignal.put(startSignalBuffer);

      final String rec_file_path = file_path;
      Thread t = new Thread(new Runnable() {
        @Override
        public void run() {
          runAAudio(referenceData, playbackBuffer.length / 2 /*16bit*/, startSignal,
              startSignalBuffer.length / 2, rec_file_path);
        }
      });
      t.start();

    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public static String[] retrieveNotGrantedPermissions(Context context) {
    ArrayList<String> nonGrantedPerms = new ArrayList<>();
    try {
      String[] manifestPerms =
          context.getPackageManager()
              .getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS)
              .requestedPermissions;
      if (manifestPerms == null || manifestPerms.length == 0) {
        return null;
      }

      for (String permName : manifestPerms) {
        int permission = ActivityCompat.checkSelfPermission(context, permName);
        if (permission != PackageManager.PERMISSION_GRANTED) {
          nonGrantedPerms.add(permName);
        }
      }
    } catch (PackageManager.NameNotFoundException e) {
    }

    return nonGrantedPerms.toArray(new String[nonGrantedPerms.size()]);
  }

  private void runAAudio(ByteBuffer reference, int refSize, ByteBuffer startSignal,
      int startSignalSize, String recPath) {
    AudioManager aman = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    AudioDeviceInfo[] adevs = aman.getDevices(AudioManager.GET_DEVICES_INPUTS);

    for (AudioDeviceInfo info : adevs) {
      Log.d(LOG_ID, "Product name: " + info.getProductName());
      int[] channels = info.getChannelCounts();

      Log.d(LOG_ID, "type: " + info.getType());
      Log.d(LOG_ID, "id: " + info.getId());
      for (int channel : channels) {
        Log.d(LOG_ID, "-- ch.count: " + channel);
      }
      int[] rates = info.getSampleRates();
      for (int rate : rates) {
        Log.d(LOG_ID, "-- ch.rate: " + rate);
      }
    }
    AudioDeviceInfo info = adevs[0]; // Take the first (and best)
    Log.d(LOG_ID, "Call native");
    TestSettings settings = new TestSettings();
    settings.deviceId = info.getId();
    settings.reference = reference;
    settings.refSize = refSize;
    settings.startSignal = startSignal;
    settings.startSignalSize = startSignalSize;
    settings.timeout = mTimeout; // sec
    settings.recPath = recPath;
    settings.sampleRate = mSampelRate;
    settings.recBufferSize = mRecBufferSize;
    settings.playBufferSize = mPlayBufferSize;

    // int status = runAAudio(info.getId(), reference, refSize, startSignal, startSignalSize,
    // mSampelRate, recPath);
    int status = runAAudio(settings);
    Log.d(LOG_ID, "Done");
    System.exit(0);
  }
}
