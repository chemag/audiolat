package com.facebook.audiolat;

import java.nio.ByteBuffer;

public class TestSettings {
  public int samplerate;
  public int timeout;
  int deviceId;
  ByteBuffer endSignal;
  int endSignalSizeInBytes;
  ByteBuffer beginSignal;
  int beginSignalSizeInBytes;
  int sampleRate;
  int playoutBufferSizeInBytes;
  int recordBufferSizeInBytes;
  String outputFilePath;
  int usage;
  int timeBetweenSignals;
  int javaaudioPerformanceMode;
}
