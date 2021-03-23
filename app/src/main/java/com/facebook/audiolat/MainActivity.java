package com.facebook.audiolat;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.ToggleButton;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
  public static final String LOG_ID = "audiolat";
  Handler mHandler;
  // default values
  int mSampleRate = 16000;
  int mTimeout = 15;
  int mRecordBufferSize = 32;
  int mPlayoutBufferSize = 32;
  int mBeginSignal = R.raw.begin_signal;
  int mEndSignal = R.raw.chirp2_16k_300ms;
  String mSignal = "chirp";
  int mUsage = AudioAttributes.USAGE_UNKNOWN;
  int mTimeBetweenSignals = 2;
  public String AAUDIO = "aaudio";
  public String JAVAAUDIO = "javaaudio";
  String mApi = AAUDIO;
  int mJavaaudioPerformanceMode = 0;
  boolean mMidiMode = false;
  int mMidiId = -1;
  MidiDeviceInfo mMidiDeviceInfo;
  static {
    System.loadLibrary("audiolat");
  }

  public native int runAAudio(TestSettings settings);
  public native void midiSignal(long nanotime);

  protected void getMidiId(MidiManager midiManager, Handler handler) {
    // check midi id
    boolean foundMidiId = false;
    MidiDeviceInfo[] infos = midiManager.getDevices();
    for (MidiDeviceInfo info : infos) {
      Bundle bundle = info.getProperties();
      Log.d(LOG_ID,
          "MidiDeviceInfo { "
              + "id: " + info.getId() + " "
              + "inputPortCount() : " + info.getInputPortCount() + " "
              + "outputPortCount() : " + info.getOutputPortCount() + " "
              + "product: " + bundle.get("product").toString() + " "
              + "}");
      if (mMidiId == -1) {
        Log.d(LOG_ID,
            "default midiid mapped to first device "
                + " "
                + "midiid: " + info.getId() + " "
                + "product: " + bundle.get("product").toString());
        mMidiId = info.getId();
      }
      if (info.getId() == mMidiId) {
        // found it
        foundMidiId = true;
        mMidiDeviceInfo = info;
        // now check there are valid output ports
        if (info.getOutputPortCount() <= 0) {
          Log.e(LOG_ID,
              "MidiDeviceInfo invalid output port count "
                  + "id: " + info.getId() + " "
                  + "outputPortCount() : " + info.getOutputPortCount());
          System.exit(-1);
        }
        // do not break so that we print all the MidiDeviceInfo's
      }
    }
    if (!foundMidiId) {
      Log.e(LOG_ID,
          "MidiDeviceInfo invalid midiid "
              + "midiid: " + mMidiId + " ");
      System.exit(-1);
    }
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // make sure the right permissions are set
    String[] permissions = retrieveNotGrantedPermissions(this);

    if (permissions != null && permissions.length > 0) {
      int REQUEST_ALL_PERMISSIONS = 0x4562;
      ActivityCompat.requestPermissions(this, permissions, REQUEST_ALL_PERMISSIONS);
    }

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
        if (extras.containsKey("signal")) {
          mSignal = extras.getString("signal");
        }
        if (extras.containsKey("usage")) {
          String usage = extras.getString("usage");
          Log.d(LOG_ID, "Set usage" + usage);
          mUsage = Integer.parseInt(usage);
        }
        if (extras.containsKey("tbs")) {
          String tbs = extras.getString("tbs");
          mTimeBetweenSignals = Integer.parseInt(tbs);
        }
        if (extras.containsKey("api")) {
          mApi = extras.getString("api");
        }
        if (extras.containsKey("atpm")) {
          String atpm = extras.getString("atpm");
          mJavaaudioPerformanceMode = Integer.parseInt(atpm);
        }
        if (extras.containsKey("midi")) {
          mMidiMode = true;
        }
        if (extras.containsKey("midiid")) {
          String midiid = extras.getString("midiid");
          mMidiId = Integer.parseInt(midiid);
        }
      }

      setContentView(R.layout.activity_main);
      String file_path = "/sdcard/audiolat";

      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

      // choose end signal file
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
      final String recFilePath = filePath;
      if (!mMidiMode) {
        Thread t = new Thread(new Runnable() {
          @Override
          public void run() {
            // pack all the info together into settings
            TestSettings settings = new TestSettings();
            settings.endSignal = endSignal;
            settings.endSignalSize = endSignalSize / 2; /* 16 bits */
            settings.beginSignal = beginSignal;
            settings.beginSignalSize = beginSignalSize / 2; /* 16 bits */
            settings.timeout = mTimeout; // sec
            settings.outputFilePath = recFilePath;
            settings.sampleRate = mSampleRate;
            settings.recordBufferSize = mRecordBufferSize;
            settings.playoutBufferSize = mPlayoutBufferSize;
            settings.usage = mUsage;
            settings.timeBetweenSignals = mTimeBetweenSignals;
            settings.javaaudioPerformanceMode = mJavaaudioPerformanceMode;
            runExperiment(mApi, settings);
          }
        });
        t.start();
      } else {
        MidiManager midiManager = (MidiManager) this.getSystemService(Context.MIDI_SERVICE);
        mHandler = Handler.createAsync(getMainLooper());
        getMidiId(midiManager, mHandler);

        // disable automatic playback
        mTimeBetweenSignals = -1;

        // pack all the info together into settings
        final TestSettings settings = new TestSettings();
        settings.endSignal = endSignal;
        settings.endSignalSize = endSignalSize / 2; /* 16 bits */
        settings.beginSignal = beginSignal;
        settings.beginSignalSize = beginSignalSize / 2; /* 16 bits */
        settings.timeout = mTimeout; // sec
        settings.outputFilePath = recFilePath;
        settings.sampleRate = mSampleRate;
        settings.recordBufferSize = mRecordBufferSize;
        settings.playoutBufferSize = mPlayoutBufferSize;
        settings.usage = mUsage;
        settings.timeBetweenSignals = -1; // no automatic playback please
        settings.javaaudioPerformanceMode = mJavaaudioPerformanceMode;

        // open the midi device
        midiManager.openDevice(mMidiDeviceInfo, new MidiManager.OnDeviceOpenedListener() {
          @Override
          public void onDeviceOpened(MidiDevice device) {
            // Just open the first port (and in most cases the only one)
            MidiOutputPort output = device.openOutputPort(0);
            if (output != null) {
              output.onConnect(new MidiReceiver() {
                @Override
                public void onSend(byte[] msg, int offset, int count, long timestamp)
                    throws IOException {
                  midiSignal(timestamp);
                  long time = System.nanoTime();
                  Log.d(LOG_ID,
                      "Got midi: timestamp = " + timestamp + " sys time " + time
                          + " diff: " + (time - timestamp));
                }
              });
            } else {
              Log.d(LOG_ID, "Failed to first port");
            }
          }
        }, mHandler);
        (new Thread(new Runnable() {
          @Override
          public void run() {
            runExperiment(mApi, settings);
          }
        })).start();
      }

    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private String setupSignalSource() {
    String filePath = "/sdcard/audiolat";
    if (mSignal.equals("chirp")) {
      // choose end signal file
      switch (mSampleRate) {
        case 48000:
          mEndSignal = R.raw.chirp2_48k_300ms;
          filePath += "_chirp2_48k_300ms.raw";
          break;
        case 16000:
          mEndSignal = R.raw.chirp2_16k_300ms;
          filePath += "_chirp2_16k_300ms.raw";
          break;
        case 8000:
          mEndSignal = R.raw.chirp2_8k_300ms;
          filePath += "_chirp2_8k_300ms.raw";
          break;
        default:
          Log.d(LOG_ID, "Unsupported sample rate:" + mSampleRate);
      }
    } else if (mSignal.equals("noise")) {
      switch (mSampleRate) {
        case 48000:
          mEndSignal = R.raw.bp_noise2_48k_300ms;
          filePath += "_bp_noise2_48k_300ms.raw";
          break;
        case 16000:
          mEndSignal = R.raw.bp_noise2_16k_300ms;
          filePath += "_bp_noise2_16k_300ms.raw";
          break;
        case 8000:
          mEndSignal = R.raw.bp_noise2_8k_300ms;
          filePath += "_bp_noise2_8k_300ms.raw";
          break;
        default:
          Log.d(LOG_ID, "Unsupported sample rate:" + mSampleRate);
      }
    }
    return filePath;
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

  private void runExperiment(String api, TestSettings settings) {
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

    AudioDeviceInfo info = adevs[0]; // Take the first (and best)
    settings.deviceId = info.getId();
    if (api.equals(AAUDIO)) {
      Log.d(LOG_ID, "Calling native (AAudio) API");
      int status = runAAudio(settings);
    } else if (api.equals(JAVAAUDIO)) {
      Log.d(LOG_ID, "Calling java (JavaAudio) API");
      JavaAudio javaAudio = new JavaAudio();
      javaAudio.runJavaAudio(this, settings);
    }

    Log.d(LOG_ID, "Done");

    System.exit(0);
  }
}
