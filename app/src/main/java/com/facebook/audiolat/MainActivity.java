package com.facebook.audiolat;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;


public class MainActivity extends AppCompatActivity {
  public static final String LOG_ID = "audiolat";

  // default values
  int mSampleRate = 16000;
  int mTimeout = 15;
  int mRecordBufferSize = 32;
  int mPlayoutBufferSize = 32;
  int mBeginSignal = R.raw.begin_signal;
  int mEndSignal = R.raw.chirp2_16k_300ms;
  public String AAUDIO = "aaudio";
  public String JAVAAUDIO = "javaaudio";
  String mApi = AAUDIO;
  String mSignal = "noise";
  int mUsage = AudioAttributes.USAGE_UNKNOWN;

  static {
    System.loadLibrary("audiolat");
  }

  public native int runAAudio(TestSettings settings);

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // make sure the right permissions are set
    String[] permissions = retrieveNotGrantedPermissions(this);

    if (permissions != null && permissions.length > 0) {
      int REQUEST_ALL_PERMISSIONS = 0x4562;
      ActivityCompat.requestPermissions(this, permissions, REQUEST_ALL_PERMISSIONS);
    }

    setContentView(R.layout.activity_main);


    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

    try {
      // read CLI arguments
      Bundle extras = this.getIntent().getExtras();

      if (extras != null) {
        if (extras.containsKey("sr")) {
          String rate = extras.getString("sr");
          mSampleRate = Integer.parseInt(rate);
        }
        if (extras.containsKey("t")) {
          String timeout = extras.getString("t");
          mTimeout = Integer.parseInt(timeout);
        }
        if (extras.containsKey("rbs")) {
          String rbs = extras.getString("rbs");
          mRecordBufferSize = Integer.parseInt(rbs);
        }
        if (extras.containsKey("pbs")) {
          String pbs = extras.getString("pbs");
          mPlayoutBufferSize = Integer.parseInt(pbs);
        }
        if (extras.containsKey("api")) {
          mApi = extras.getString("api");
        }
        if (extras.containsKey("signal")) {
          mSignal = extras.getString("signal");
        }
        if (extras.containsKey("usage")) {
          String usage = extras.getString("usage");
          Log.d(LOG_ID, "Set usage" + usage);
          mUsage = Integer.parseInt(usage);
        }
      }
      String filePath = setupSignalSource();

      // read the end signal into endSignal
      InputStream is = this.getResources().openRawResource(mEndSignal);
      final int endSignalSize = is.available();
      final byte[] endSignalBuffer = new byte[endSignalSize];

      int read = is.read(endSignalBuffer);
      final ByteBuffer endSignal = ByteBuffer.allocateDirect(read);
      endSignal.put(endSignalBuffer); // Small files only :)
      is.close();

      // read the begin signal into beginSignal
      is = this.getResources().openRawResource(mBeginSignal);
      final int beginSignalSize = is.available();
      final byte[] beginSignalBuffer = new byte[beginSignalSize];

      read = is.read(beginSignalBuffer);
      final ByteBuffer beginSignal = ByteBuffer.allocateDirect(read);
      beginSignal.put(beginSignalBuffer);

      // begin a thread that implements the experiment
      final String rec_file_path = filePath;
      Thread t = new Thread(new Runnable() {
        @Override
        public void run() {

          TestSettings settings = new TestSettings();
          settings.endSignal = endSignal;
          settings.endSignalSize = endSignalBuffer.length / 2;
          settings.beginSignal = beginSignal;
          settings.beginSignalSize = beginSignalBuffer.length / 2;
          settings.timeout = mTimeout; // sec
          settings.outputFilePath = rec_file_path;
          settings.sampleRate = mSampleRate;
          settings.recordBufferSize = mRecordBufferSize;
          settings.playoutBufferSize = mPlayoutBufferSize;
          settings.timeBetweenSignals = 2;
          settings.usage = mUsage;

          runRec(mApi, settings);
        }
      });
      t.start();

    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private String setupSignalSource() {
    String file_path = "/sdcard/audiolat";
    if (mSignal.equals("chirp")) {
      // choose end signal file
      switch (mSampleRate) {
        case 48000:
          mEndSignal = R.raw.chirp2_48k_300ms;
          file_path += "_chirp2_48k_300ms.raw";
          break;
        case 16000:
          mEndSignal = R.raw.chirp2_16k_300ms;
          file_path += "_chirp2_16k_300ms.raw";
          break;
        case 8000:
          mEndSignal = R.raw.chirp2_8k_300ms;
          file_path += "_chirp2_8k_300ms.raw";
          break;
        default:
          Log.d(LOG_ID, "Unsupported sample rate:" + mSampleRate);
      }
    } else if (mSignal.equals("noise")) {
      switch (mSampleRate) {
        case 48000:
          mEndSignal = R.raw.bp_noise2_48k_300ms;
          file_path += "_bp_noise2_48k_300ms.raw";
          break;
        case 16000:
          mEndSignal = R.raw.bp_noise2_16k_300ms;
          file_path += "_bp_noise2_16k_300ms.raw";
          break;
        case 8000:
          mEndSignal = R.raw.bp_noise2_8k_300ms;
          file_path += "_bp_noise2_8k_300ms.raw";
          break;
        default:
          Log.d(LOG_ID, "Unsupported sample rate:" + mSampleRate);
      }
    } 

    return file_path;
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

  private void runRec(String api, TestSettings settings) {
    AudioManager aman = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    AudioDeviceInfo[] adevs = aman.getDevices(AudioManager.GET_DEVICES_INPUTS);

    for (AudioDeviceInfo info : adevs) {
      Log.d(LOG_ID, "product_name: " + info.getProductName());
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

    // pack all the info together into settings
    AudioDeviceInfo info = adevs[0]; // Take the first (and best)

    settings.deviceId = info.getId();
    if (api.equals(AAUDIO)) {
      int status = runAAudio(settings);
    } else if (api.equals(JAVAAUDIO)) {
      JavaRecord rec = new JavaRecord();
      rec.record(this, settings);
    }
    Log.d(LOG_ID, "Done");
    System.exit(0);
  }
}
