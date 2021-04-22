package com.facebook.audiolat;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Vector;

public class MainActivity extends AppCompatActivity {
  public static final String LOG_ID = "audiolat";
  Handler mHandler;
  // default values
  int mSampleRate = 16000;
  int mTimeout = 15;
  int mRecordBufferSizeInBytes = 32;
  int mPlayoutBufferSizeInBytes = 32;
  int mBeginSignal = R.raw.begin_signal;
  int mEndSignal = R.raw.chirp2_16k_300ms;
  String mSignal = "chirp";
  int mUsage = AudioAttributes.USAGE_GAME;
  int mTimeBetweenSignals = 2;
  public String AAUDIO = "aaudio";
  public String JAVAAUDIO = "javaaudio";
  public String OBOE = "oboe";
  // default backend
  String mApi = AAUDIO;
  JavaAudio mJavaAudio;
  int mJavaaudioPerformanceMode = 0;
  // experiment type
  // * true: downlink-only experiment (either midi- or usb-based)
  // * false: full (downlink+uplink) experiment
  boolean mMidiMode = false;
  // midi device id (-2 to use plain USB)
  int mMidiId = -1;
  MidiDeviceInfo mMidiDeviceInfo;
  UsbMidi mUsbMidi;

  static {
    if (Build.VERSION.SDK_INT >= 29) {
      System.loadLibrary("audiolat");
    } else {
      System.loadLibrary("audiolat_sdk28");
    }
  }

  public native int runAAudio(TestSettings settings);
  public native int runOboe(TestSettings settings);
  public native void aaudioMidiSignal(long nanotime);
  public native void oboeMidiSignal(long nanotime);
  public native void startReadingMidi(MidiDevice device, int portNumber);

  PendingIntent mPermissionIntent;

  private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
  private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();
      if (ACTION_USB_PERMISSION.equals(action)) {
        synchronized (this) {
          UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

          if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            if (device != null) {
              startUsbMidi(device);
            }
          } else {
            Log.d(LOG_ID, "usb: permission denied for device " + device);
          }
        }
      }
    }
  };

  protected void getMidiId(MidiManager midiManager, Handler handler) {
    // check midi id
    boolean foundMidiId = false;
    MidiDeviceInfo[] infos = midiManager.getDevices();
    // check there is at least a valid midi id
    if (infos.length < 1) {
      Log.e(LOG_ID, "midi: MidiDeviceInfo no midi devices available");
      System.exit(-1);
    }
    for (MidiDeviceInfo info : infos) {
      Bundle bundle = info.getProperties();
      Log.d(LOG_ID,
          "midi: MidiDeviceInfo { "
              + "id: " + info.getId() + " "
              + "inputPortCount(): " + info.getInputPortCount() + " "
              + "outputPortCount(): " + info.getOutputPortCount() + " "
              + "product: " + bundle.get("product").toString() + " "
              + "}");
      if (mMidiId == -1) {
        Log.d(LOG_ID,
            "midi: default midiid mapped to first device "
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
              "midi: MidiDeviceInfo invalid output port count "
                  + "id: " + info.getId() + " "
                  + "outputPortCount() : " + info.getOutputPortCount());
          System.exit(-1);
        }
        // do not break so that we print all the MidiDeviceInfo's
      }
    }
    if (!foundMidiId) {
      Log.e(LOG_ID,
          "midi: MidiDeviceInfo invalid midiid "
              + "midiid: " + mMidiId);
      System.exit(-1);
    }
  }

  protected void readCliArguments() {
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
        mRecordBufferSizeInBytes = Integer.parseInt(rbs);
      }
      if (extras.containsKey("pbs")) {
        String pbs = extras.getString("pbs");
        mPlayoutBufferSizeInBytes = Integer.parseInt(pbs);
      }
      if (extras.containsKey("signal")) {
        mSignal = extras.getString("signal");
      }
      if (extras.containsKey("usage")) {
        String usage = extras.getString("usage");
        Log.d(LOG_ID, "main: set usage" + usage);
        mUsage = Integer.parseInt(usage);
      }
      if (extras.containsKey("tbs")) {
        String tbs = extras.getString("tbs");
        mTimeBetweenSignals = Integer.parseInt(tbs);
      }
      if (extras.containsKey("api")) {
        mApi = extras.getString("api");
        // check the value
        if (!mApi.equals(AAUDIO) && !mApi.equals(OBOE) && !mApi.equals(JAVAAUDIO)) {
          Log.e(LOG_ID, "main: invalid API type: \"" + mApi + "\"");
          System.exit(-1);
        }
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
  }

  protected TestSettings buildTestSettings(final ByteBuffer endSignal,
      final int endSignalSizeInBytes, final ByteBuffer beginSignal,
      final int beginSignalSizeInBytes, final String recFilePath) {
    TestSettings settings = new TestSettings();
    settings.endSignal = endSignal;
    settings.endSignalSizeInBytes = endSignalSizeInBytes;
    settings.beginSignal = beginSignal;
    settings.beginSignalSizeInBytes = beginSignalSizeInBytes;
    settings.timeout = mTimeout; // sec
    settings.outputFilePath = recFilePath;
    settings.sampleRate = mSampleRate;
    settings.recordBufferSizeInBytes = mRecordBufferSizeInBytes;
    settings.playoutBufferSizeInBytes = mPlayoutBufferSizeInBytes;
    settings.usage = mUsage;
    settings.timeBetweenSignals = mTimeBetweenSignals;
    settings.javaaudioPerformanceMode = mJavaaudioPerformanceMode;
    return settings;
  }

  protected void createUsbConnection() {
    Log.d(LOG_ID, "usb: create usb connection");

    mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
    IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
    registerReceiver(usbReceiver, filter);

    UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
    UsbAccessory[] accs = manager.getAccessoryList();
    HashMap<String, UsbDevice> devices = manager.getDeviceList();
    Vector<UsbDeviceConnection> connected = new Vector<>();

    java.util.Set<String> keys = devices.keySet();
    if (keys.size() == 0) {
      Log.d(LOG_ID, "usb: no keys");
    }
    for (String key : keys) {
      Log.d(LOG_ID, String.format("usb: device: %s key: %s", key, devices.get(key)));
      UsbDevice device = devices.get(key);
      manager.requestPermission(device, mPermissionIntent);
    }
  }

  protected void createMidiConnection() {
    Log.d(LOG_ID, "midi: create midi connection");
    MidiManager midiManager = (MidiManager) this.getSystemService(Context.MIDI_SERVICE);
    mHandler = Handler.createAsync(getMainLooper());
    getMidiId(midiManager, mHandler);

    // open the midi device
    midiManager.openDevice(mMidiDeviceInfo, new MidiManager.OnDeviceOpenedListener() {
      @Override
      public void onDeviceOpened(MidiDevice device) {
        // Just open the first port (and in most cases the only one)
        MidiOutputPort output = device.openOutputPort(0);
        if (output != null) {
          if (Build.VERSION.SDK_INT >= 29 && mApi.equals(AAUDIO)) {
            startReadingMidi(device, 0);
          } else {
            output.onConnect(new MidiReceiver() {
              long mLastEventTs = 0;
              @Override
              public void onSend(byte[] msg, int offset, int count, long timestamp)
                  throws IOException {
                if (timestamp - mLastEventTs / 1000000 > 1000) {
                  triggerMidi(timestamp);
                  long nanoTime = System.nanoTime();
                  Log.d(LOG_ID,
                      "midi: received midi data: "
                          + "timestamp: " + timestamp + " "
                          + "nanoTime: " + nanoTime + " "
                          + "diff: " + (nanoTime - timestamp));
                }
                mLastEventTs = timestamp;
              }
            });
          }
        } else {
          Log.d(LOG_ID, "midi: failed to first port");
        }
      }
    }, mHandler);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // make sure the right permissions are set
    String[] permissions = retrieveNotGrantedPermissions(this);

    if (permissions != null && permissions.length > 0) {
      int REQUEST_ALL_PERMISSIONS = 0x4562;
      Log.d(LOG_ID, "main: request permissions: " + permissions);
      ActivityCompat.requestPermissions(this, permissions, REQUEST_ALL_PERMISSIONS);
    }

    // get the right place to write the experiment audio files
    File[] externalStorageVolumes =
        ContextCompat.getExternalFilesDirs(getApplicationContext(), null);
    File primaryExternalStorage = externalStorageVolumes[0];

    try {
      readCliArguments();

      setContentView(R.layout.activity_main);
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

      // choose end signal file
      String filePath = setupSignalSource(primaryExternalStorage.getAbsolutePath());

      // read the end signal into endSignal
      // TODO(chema): move to a `readSignal()` function
      InputStream is = this.getResources().openRawResource(mEndSignal);
      final int endSignalSizeInBytes = is.available();
      final byte[] endSignalBuffer = new byte[endSignalSizeInBytes];

      int read_bytes = is.read(endSignalBuffer);
      final ByteBuffer endSignal = ByteBuffer.allocateDirect(read_bytes);
      endSignal.put(endSignalBuffer); // Small files only :)
      is.close();

      // read the begin signal into beginSignal
      // TODO(chema): move to a `readSignal()` function
      is = this.getResources().openRawResource(mBeginSignal);
      final int beginSignalSizeInBytes = is.available();
      final byte[] beginSignalBuffer = new byte[beginSignalSizeInBytes];

      read_bytes = is.read(beginSignalBuffer);
      final ByteBuffer beginSignal = ByteBuffer.allocateDirect(read_bytes);
      beginSignal.put(beginSignalBuffer);

      // begin a thread that implements the experiment
      final String recFilePath = filePath;
      if (!mMidiMode) {
        Thread t = new Thread(new Runnable() {
          @Override
          public void run() {
            // pack all the info together into settings
            TestSettings settings = buildTestSettings(
                endSignal, endSignalSizeInBytes, beginSignal, beginSignalSizeInBytes, recFilePath);
            runExperiment(mApi, settings);
          }
        });
        t.start();
      } else {
        // pack all the info together into settings
        // disable automatic playback
        mTimeBetweenSignals = -1;

        // pack all the info together into settings
        final TestSettings settings = buildTestSettings(
            endSignal, endSignalSizeInBytes, beginSignal, beginSignalSizeInBytes, recFilePath);

        if (mMidiId == -2) {
          createUsbConnection();

          (new Thread(new Runnable() {
            @Override
            public void run() {
              runExperiment(mApi, settings);
            }
          })).start();
          return;
        } else {
          createMidiConnection();

          (new Thread(new Runnable() {
            @Override
            public void run() {
              runExperiment(mApi, settings);
            }
          })).start();
        }
      }

    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void startUsbMidi(UsbDevice device) {
    UsbMidi midi = new UsbMidi(this);
    midi.openDevice(device, this);
  }

  public void triggerMidi(long timestamp) {
    if (mApi.equals(OBOE)) { // TODO: native oboe midi
      oboeMidiSignal(timestamp);
    } else if (mApi.equals(JAVAAUDIO) && mJavaAudio != null) {
      mJavaAudio.javaMidiSignal(timestamp);
    } else if (mApi.equals(AAUDIO)) {
      aaudioMidiSignal(timestamp);
    }
  }

  private String setupSignalSource(String filePath) {
    filePath += "/audiolat";
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
          Log.d(LOG_ID, "main: unsupported sample rate:" + mSampleRate);
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
          Log.d(LOG_ID, "main: unsupported sample rate:" + mSampleRate);
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
          Log.d(LOG_ID, "Failed to get permission for:" + permName);
        }
      }
    } catch (PackageManager.NameNotFoundException e) {
    }

    return nonGrantedPerms.toArray(new String[nonGrantedPerms.size()]);
  }

  private void runExperiment(String api, TestSettings settings) {
    AudioManager aman = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    String outputSampleRate = aman.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
    Log.d(LOG_ID, "main: outputSampleRate: " + outputSampleRate);
    String outputFramesPerBuffer = aman.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
    Log.d(LOG_ID, "main: outputFramesPerBuffer: " + outputFramesPerBuffer);

    AudioDeviceInfo[] adevs = aman.getDevices(AudioManager.GET_DEVICES_INPUTS);
    for (AudioDeviceInfo info : adevs) {
      Log.d(LOG_ID, "main: product_name: " + info.getProductName());
      int[] channels = info.getChannelCounts();

      Log.d(LOG_ID, "main: type: " + info.getType());
      Log.d(LOG_ID, "main: id: " + info.getId());
      for (int channel : channels) {
        Log.d(LOG_ID, "main: ch.count: " + channel);
      }
      int[] rates = info.getSampleRates();
      for (int rate : rates) {
        Log.d(LOG_ID, "main: ch.rate: " + rate);
      }

      if (info.isSink() && info.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
        settings.playoutDeviceId = info.getId();
      } else if (info.isSource() && info.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
        settings.playoutDeviceId = info.getId();
      }

      if (settings.playoutDeviceId != 0 && settings.recordDeviceId != 0) {
        break;
      }
    }

    if (api.equals(AAUDIO)) {
      Log.d(LOG_ID, "main: calling native (AAudio) API");
      int status = runAAudio(settings);
    } else if (api.equals(OBOE)) {
      Log.d(LOG_ID, "main: calling native (oboe) API");
      int status = runOboe(settings);
      Log.d(LOG_ID, "main: done status: " + status);
    } else if (api.equals(JAVAAUDIO)) {
      Log.d(LOG_ID, "main: calling java (JavaAudio) API");
      mJavaAudio = new JavaAudio();
      mJavaAudio.runJavaAudio(this, settings);
    }
    Log.d(LOG_ID, "main: done");

    System.exit(0);
  }
}
