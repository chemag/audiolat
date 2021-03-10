package com.facebook.latencycheck;

import java.nio.ByteBuffer;

public class TestSettings {
  public int samplerate;
  public int timeout;
  int deviceId;
  ByteBuffer reference;
  int refSize;
  ByteBuffer startSignal;
  int startSignalSize;
  int sampleRate;
  int playBufferSize;
  int recBufferSize;
  String recPath;
}
