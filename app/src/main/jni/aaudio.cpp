#define __STDC_FORMAT_MACROS 1
#include <aaudio/AAudio.h>
#include <amidi/AMidi.h>
#include <android/log.h>
#include <inttypes.h>
#include <jni.h>
#include <math.h>
#include <pthread.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <string>
//#include <android/trace.h>
#include <dlfcn.h>

#ifdef DEBUG
#define LOGD(...) \
  __android_log_print(ANDROID_LOG_DEBUG, "audiolat", __VA_ARGS__)
#else
#define LOGD(...) ""
#endif

#define LOGI(...) \
  __android_log_print(ANDROID_LOG_DEBUG, "audiolat", __VA_ARGS__)

#define LOGE(...) \
  __android_log_print(ANDROID_LOG_DEBUG, "audiolat, error: ", __VA_ARGS__)

// #define DEBUG_DATA_CALLBACK

static bool running = false;
static int64_t last_midi_nanotime = -1;
static int midi_port = 0;

#if __ANDROID_API__ >= 29
static AMidiDevice *midiDevice = NULL;
static AMidiOutputPort *midiOutputPort(NULL);
#endif

struct callback_data {
  FILE *output_file_descriptor;
  AAudioStream *record_stream;
  AAudioStream *playout_stream;
  int16_t *end_signal;
  int end_signal_size_in_frames;
  int16_t *begin_signal;
  int begin_signal_size_in_frames;
  int samplerate;
  int timeout;
  int time_between_signals;
  int rxruns;
  int pxruns;
};

bool midi_check_for_data(
#if __ANDROID_API__ >= 29
    AMidiOutputPort *midi_output_port,
#endif
    int64_t *last_midi_ts) {

  struct timespec time;
  bool triggered = false;
  clock_gettime(CLOCK_MONOTONIC, &time);
  long current_nanotime = time.tv_sec * 1000000000 + time.tv_nsec;
#if __ANDROID_API__ >= 29
  const size_t MAX_BYTES_TO_RECEIVE = 128;
  uint8_t incomingMessage[MAX_BYTES_TO_RECEIVE];
  int32_t opcode;
  size_t numBytesReceived;
  int64_t timestamp;
  ssize_t numMessagesReceived = AMidiOutputPort_receive(
      midiOutputPort, &opcode, incomingMessage, MAX_BYTES_TO_RECEIVE,
      &numBytesReceived, &timestamp);

  if (numMessagesReceived > 0) {
    if ((timestamp - *last_midi_ts) / 1000000 > 1000) {
      *last_midi_ts = timestamp;
      triggered = true;
    }
  }
#else
  if ((last_midi_nanotime - *last_midi_ts) / 1000000 > 1000) {
    last_midi_nanotime = *last_midi_ts;
    triggered = true;
  }
#endif
#ifdef DEBUG_DATA_CALLBACK
  LOGD(
      "playout midi triggered in full "
      "last_midi_nanotime: %" PRIi64
      " "
      "current_nanotime: %ld "
      "difference_ms: %" PRIi64,
      *last_midi_ts, current_nanotime,
      (current_nanotime - *last_midi_ts) / 1000000);
#endif  // DEBUG_DATA_CALLBACK

  return triggered;
}

aaudio_data_callback_result_t dataCallback(AAudioStream *stream, void *userData,
                                           void *audioData,
                                           int32_t num_frames) {
  static int written_frames = 0;
  static int playout_num_frames_remaining = 0;
  static int record_num_frames_remaining = 0;
  static float last_ts = 0;
  static int64_t last_midi_ts = -1;
  struct callback_data *cb_data = (struct callback_data *)userData;
  aaudio_stream_state_t playout_state =
      AAudioStream_getState(cb_data->playout_stream);
  float time_sec = (float)written_frames / (float)cb_data->samplerate;

#ifdef DEBUG_DATA_CALLBACK
  LOGD(
      "dataCallback type: %s num_frames: %d time_sec: %.2f "
      "playout_num_frames_remaining: %d record_num_frames_remaining: %d",
      (stream == cb_data->record_stream) ? "record" : "playout", num_frames,
      time_sec, playout_num_frames_remaining, record_num_frames_remaining);
#endif  // DEBUG_DATA_CALLBACK

  int rxrun = AAudioStream_getXRunCount(cb_data->record_stream);
  int pxrun = AAudioStream_getXRunCount(cb_data->playout_stream);

  if (rxrun) {
    LOGE("XRUN in record: %d, total: %d", rxrun, cb_data->pxruns);
    int frames_per_burst =
        AAudioStream_getFramesPerBurst(cb_data->record_stream);
    int current_buffer_size_in_frames =
        AAudioStream_getBufferSizeInFrames(cb_data->record_stream);
    AAudioStream_setBufferSizeInFrames(
        cb_data->record_stream,
        current_buffer_size_in_frames + frames_per_burst);
    LOGI("Add %d to record buffer, current: %d", frames_per_burst,
         current_buffer_size_in_frames);
    cb_data->rxruns += rxrun;
  }
  if (pxrun) {
    LOGE("XRUN in playout:  %d, total: %d", pxrun, cb_data->pxruns);
    int frames_per_burst =
        AAudioStream_getFramesPerBurst(cb_data->playout_stream);
    int current_buffer_size_in_frames =
        AAudioStream_getBufferSizeInFrames(cb_data->playout_stream);
    AAudioStream_setBufferSizeInFrames(
        cb_data->playout_stream,
        current_buffer_size_in_frames + frames_per_burst);
    LOGI("Add %d to playback buffer, current: %d", frames_per_burst,
         current_buffer_size_in_frames);
    cb_data->pxruns += pxrun;
  }
  // Read MIDI Data
  if (midi_check_for_data(
#if __ANDROID_API__ >= 29
          midiOutputPort,
#endif
          &last_midi_ts)) {
    playout_num_frames_remaining = cb_data->end_signal_size_in_frames;
  }

  if (stream == cb_data->record_stream) {
    // recording
    written_frames += num_frames;
    if ((cb_data->time_between_signals > 0 &&
         time_sec - last_ts > cb_data->time_between_signals)) {
      // experiment start: we need, as soon as possible, to:
      // 1. play out the end signal
      playout_num_frames_remaining = cb_data->end_signal_size_in_frames;
      // 2. record the begin signal
      record_num_frames_remaining = cb_data->begin_signal_size_in_frames;
      // 3. set the time for the next experiment
      last_ts = time_sec;
#ifdef DEBUG_DATA_CALLBACK
      LOGD(
          "experiment playout_num_frames_remaining: %d "
          "record_num_frames_remaining: %d",
          playout_num_frames_remaining, record_num_frames_remaining);
#endif  // DEBUG_DATA_CALLBACK
    }
    int record_buffer_offset = 0;
    if (record_num_frames_remaining > 0 &&
        record_num_frames_remaining < cb_data->begin_signal_size_in_frames) {
      // we are in the middle of recording the begin signal:
      // Let's write it on the left side
      // +--------------------------------+
      // |BBBB                            |
      // +--------------------------------+
      int num_frames_to_write =
          std::min(record_num_frames_remaining, num_frames);
      int begin_signal_offset =
          cb_data->begin_signal_size_in_frames - record_num_frames_remaining;
#ifdef DEBUG_DATA_CALLBACK
      LOGD("record source: begin num_frames: %d remaining: %d",
           num_frames_to_write, record_num_frames_remaining);
#endif  // DEBUG_DATA_CALLBACK
      fwrite(cb_data->begin_signal + begin_signal_offset,
             (size_t)num_frames_to_write, sizeof(int16_t),
             cb_data->output_file_descriptor);
      num_frames -= num_frames_to_write;
      record_num_frames_remaining -= num_frames_to_write;
      record_buffer_offset += num_frames_to_write;
    }
    if (num_frames > record_num_frames_remaining) {
      // Let's write the mic input
      // +--------------------------------+
      // |    MMMMMMMMMMMMMMMMMMMMMMMM    |
      // +--------------------------------+
      int num_frames_to_write = num_frames - record_num_frames_remaining;
#ifdef DEBUG_DATA_CALLBACK
      LOGD("record source: input num_frames: %d", num_frames_to_write);
#endif  // DEBUG_DATA_CALLBACK
      fwrite(((int16_t *)audioData) + record_buffer_offset, sizeof(int16_t),
             (size_t)num_frames_to_write, cb_data->output_file_descriptor);
      num_frames -= num_frames_to_write;
    }
    if (record_num_frames_remaining == cb_data->begin_signal_size_in_frames) {
      // we are at the beginning of recording the begin signal:
      // Let's write it on the right side
      // +--------------------------------+
      // |                            BBBB|
      // +--------------------------------+
      int num_frames_to_write =
          std::min(record_num_frames_remaining, num_frames);
#ifdef DEBUG_DATA_CALLBACK
      LOGD("record source: begin num_frames: %d remaining: %d",
           num_frames_to_write, record_num_frames_remaining);
#endif  // DEBUG_DATA_CALLBACK
      fwrite(cb_data->begin_signal, (size_t)num_frames_to_write,
             sizeof(int16_t), cb_data->output_file_descriptor);
      num_frames -= num_frames_to_write;
      record_num_frames_remaining -= num_frames_to_write;
    }

    LOGD("record written_frames: %d", written_frames);
    if (time_sec > cb_data->timeout) {
      running = false;
    }

  } else {
    // playout
#ifdef DEBUG_DATA_CALLBACK
    LOGD("******  playout num_frames: %d time_sec: %.2f", num_frames, time_sec);
#endif  // DEBUG_DATA_CALLBACK
    int playout_buffer_offset = 0;

    if ((playout_num_frames_remaining > 0) &&
        (playout_state == AAUDIO_STREAM_STATE_STARTED)) {
      // we are in the middle of playing the end signal:
      // Let's write it on the left side
      // +--------------------------------+
      // |EEEEEEEE                        |
      // +--------------------------------+
#ifdef DEBUG_DATA_CALLBACK
      LOGD("playout source: end num_frames: %d remaining: %d", num_frames,
           playout_num_frames_remaining);
#endif  // DEBUG_DATA_CALLBACK
      int num_frames_to_write =
          std::min(num_frames, playout_num_frames_remaining);
      int end_signal_offset =
          cb_data->end_signal_size_in_frames - playout_num_frames_remaining;
      memcpy(audioData, cb_data->end_signal + end_signal_offset,
             sizeof(int16_t) * num_frames_to_write);
      num_frames -= num_frames_to_write;
      playout_num_frames_remaining -= num_frames_to_write;
      playout_buffer_offset += num_frames_to_write;
    }
    if (num_frames > 0) {
      // we are out of signal: play out silence
      // Let's write it on the right side
      // +--------------------------------+
      // |        SSSSSSSSSSSSSSSSSSSSSSSS|
      // +--------------------------------+
#ifdef DEBUG_DATA_CALLBACK
      LOGD("playout source: silence num_frames: %d", num_frames);
#endif  // DEBUG_DATA_CALLBACK
      memset((void *)(((int16_t *)audioData) + playout_buffer_offset), 0,
             sizeof(int16_t) * num_frames);
    }
  }

  return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void log_current_settings(AAudioStream *playout_stream,
                          AAudioStream *record_stream) {
  // print current settings
  int playout_frames_per_burst = AAudioStream_getFramesPerBurst(playout_stream);
  int playout_buffer_capacity =
      AAudioStream_getBufferCapacityInFrames(playout_stream);
  int playout_current_buffer_size_in_frames =
      AAudioStream_getBufferSizeInFrames(playout_stream);
  int playout_samplerate = AAudioStream_getSampleRate(playout_stream);

  int record_frames_per_burst = AAudioStream_getFramesPerBurst(record_stream);
  int record_buffer_capacity =
      AAudioStream_getBufferCapacityInFrames(record_stream);
  int record_current_buffer_size_in_frames =
      AAudioStream_getBufferSizeInFrames(record_stream);
  int record_samplerate = AAudioStream_getSampleRate(record_stream);

  LOGI("info: playout sample rate: %d", playout_samplerate);
  LOGI("info: playout frames_per_burst: %d, %.2f ms", playout_frames_per_burst,
       1000 * (float)playout_frames_per_burst / (float)playout_samplerate);
  LOGI("info: playout current_buffer_size_in_frames: %d, %.2f ms",
       playout_current_buffer_size_in_frames,
       1000 * (float)playout_current_buffer_size_in_frames /
           (float)playout_samplerate);
  LOGI("info: playout buffer_capacity: %d",
       AAudioStream_getBufferSizeInFrames(playout_stream));
  LOGI("info: playout performance_mode: %d",
       AAudioStream_getPerformanceMode(playout_stream));
  LOGI("info: playout usage: %d", AAudioStream_getUsage(playout_stream));

  LOGI("info: record sample rate: %d", record_samplerate);
  LOGI("info: record frames_per_burst: %d,  %.2f ms", record_frames_per_burst,
       1000 * (float)record_frames_per_burst / (float)record_samplerate);
  LOGI("info: record current_buffer_size_in_frames: %d, %.2f ms",
       record_current_buffer_size_in_frames,
       1000 * (float)record_current_buffer_size_in_frames /
           (float)record_samplerate);
  LOGI("info: record buffer_capacity: %d", record_buffer_capacity);
  LOGI("info: record performance_mode: %d",
       AAudioStream_getPerformanceMode(record_stream));
  LOGI("info: record input preset: %d",
       AAudioStream_getInputPreset(record_stream));
}

extern "C" JNIEXPORT void JNICALL
Java_com_facebook_audiolat_MainActivity_aaudioMidiSignal(JNIEnv *env,
                                                         jobject /* this */,
                                                         jlong nanotime) {
  last_midi_nanotime = nanotime;
}

extern "C" JNIEXPORT void JNICALL
Java_com_facebook_audiolat_MainActivity_startReadingMidi(JNIEnv *env, jobject,
                                                         jobject deviceObj,
                                                         jint portNumber) {
#if __ANDROID_API__ >= 29
  AMidiDevice_fromJava(env, deviceObj, &midiDevice);
  LOGD("Open midi device");
  int32_t result =
      AMidiOutputPort_open(midiDevice, portNumber, &midiOutputPort);
  if (result) {
    LOGD("Failed to open midi device and port: %d", portNumber);
  } else {
    LOGD("Opened midi device and port: %d", portNumber);
    midi_port = portNumber;
  }
#endif
}

// main experiment function
extern "C" JNIEXPORT jint JNICALL
Java_com_facebook_audiolat_MainActivity_runAAudio(JNIEnv *env,
                                                  jobject /* this */,
                                                  jobject settings) {
  // unpack the test utils
  jclass cSettings = env->GetObjectClass(settings);
  jfieldID fid =
      env->GetFieldID(cSettings, "endSignal", "Ljava/nio/ByteBuffer;");
  jobject end_signal = env->GetObjectField(settings, fid);
  fid = env->GetFieldID(cSettings, "endSignalSizeInBytes", "I");
  jint end_signal_size_in_bytes = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "beginSignal", "Ljava/nio/ByteBuffer;");
  jobject begin_signal = env->GetObjectField(settings, fid);
  fid = env->GetFieldID(cSettings, "beginSignalSizeInBytes", "I");
  jint begin_signal_size_in_bytes = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "sampleRate", "I");
  jint sample_rate = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "outputFilePath", "Ljava/lang/String;");
  jstring output_file_path = (jstring)env->GetObjectField(settings, fid);
  fid = env->GetFieldID(cSettings, "timeout", "I");
  jint timeout = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "playoutBufferSizeInBytes", "I");
  jint playout_buffer_size_in_bytes = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "recordBufferSizeInBytes", "I");
  jint record_buffer_size_in_bytes = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "usage", "I");
  jint usage = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "inputPreset", "I");
  jint inpreset = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "timeBetweenSignals", "I");
  jint time_between_signals = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "recordDeviceId", "I");
  jint record_device_id = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "playoutDeviceId", "I");
  jint playout_device_id = env->GetIntField(settings, fid);

  running = true;

  struct callback_data cb_data;
  memset(&cb_data, 0, sizeof(struct callback_data));
  LOGD("** SAMPLE RATE == %d **", sample_rate);

  aaudio_result_t result;
  AAudioStream *playout_stream = nullptr;
  AAudioStream *record_stream = nullptr;
  AAudioStreamBuilder *playout_builder = nullptr;
  AAudioStreamBuilder *record_builder = nullptr;
  int16_t *end_signal_buffer = nullptr;
  int16_t *begin_signal_buffer = nullptr;
  int playout_xrun = 0;
  int record_xrun = 0;

  // open the output file
  const char *output_file_name = env->GetStringUTFChars(output_file_path, 0);
  LOGD("Open file: %s", output_file_name);
  FILE *output_file_descriptor = fopen(output_file_name, "wb");
  if (output_file_descriptor == nullptr) {
    LOGD("Failed to open file");
    goto cleanup;
  }

  // get access to the begin and end signal buffers
  end_signal_buffer = (int16_t *)env->GetDirectBufferAddress(end_signal);
  if (!end_signal_buffer) {
    LOGD("Failed to get direct buffer");
    return -1;
  }
  begin_signal_buffer = (int16_t *)env->GetDirectBufferAddress(begin_signal);
  if (!begin_signal_buffer) {
    LOGD("Failed to get direct buffer");
    return -1;
  }

  // set up the playout (downlink, speaker) audio stream
  result = AAudio_createStreamBuilder(&playout_builder);
  LOGD("Create playout stream: %d", result);
  if (result) {
    LOGD("Failed to create playout stream");
    goto cleanup;
  }
  AAudioStreamBuilder_setDeviceId(playout_builder, playout_device_id);
  AAudioStreamBuilder_setUsage(playout_builder, usage);
  AAudioStreamBuilder_setDirection(playout_builder, AAUDIO_DIRECTION_OUTPUT);
  // AAUDIO_SHARING_MODE_EXCLUSIVE no available
  AAudioStreamBuilder_setSharingMode(playout_builder,
                                     AAUDIO_SHARING_MODE_SHARED);
  AAudioStreamBuilder_setSampleRate(playout_builder, sample_rate);
  AAudioStreamBuilder_setChannelCount(playout_builder, 1);
  AAudioStreamBuilder_setFormat(playout_builder, AAUDIO_FORMAT_PCM_I16);
  AAudioStreamBuilder_setBufferCapacityInFrames(playout_builder, 64);
  AAudioStreamBuilder_setPerformanceMode(playout_builder,
                                         AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  AAudioStreamBuilder_setDataCallback(playout_builder, dataCallback, &cb_data);

  // set up the record (uplink, mic) audio stream
  result = AAudio_createStreamBuilder(&record_builder);
  LOGD("Create record stream: %d", result);
  if (result) {
    LOGD("Failed to create record stream");
    goto cleanup;
  }
  AAudioStreamBuilder_setDeviceId(record_builder, record_device_id);
  AAudioStreamBuilder_setDirection(record_builder, AAUDIO_DIRECTION_INPUT);
  // AAUDIO_SHARING_MODE_EXCLUSIVE no available

  AAudioStreamBuilder_setSampleRate(record_builder, sample_rate);
  AAudioStreamBuilder_setChannelCount(record_builder, 1);
  AAudioStreamBuilder_setFormat(record_builder, AAUDIO_FORMAT_PCM_I16);
  AAudioStreamBuilder_setPerformanceMode(record_builder,
                                         AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  AAudioStreamBuilder_setBufferCapacityInFrames(record_builder, 64);
  LOGI("set input preset: %d", inpreset);
  AAudioStreamBuilder_setInputPreset(record_builder, inpreset);

  AAudioStreamBuilder_setDataCallback(record_builder, dataCallback, &cb_data);

  // open both streams
  result = AAudioStreamBuilder_openStream(playout_builder, &playout_stream);
  LOGD("Open playout stream: %d", result);
  if (result) {
    LOGD("Failed to open playout stream");
    goto cleanup;
  }

  AAudioStreamBuilder_setSharingMode(record_builder,
                                     AAUDIO_SHARING_MODE_SHARED);
  result = AAudioStreamBuilder_openStream(record_builder, &record_stream);
  LOGD("Open record stream: %d", result);
  if (result) {
    LOGD("Failed to open record stream");
    goto cleanup;
  }

  // set stream sizes
  if (playout_buffer_size_in_bytes == 1) {
    AAudioStream_setBufferSizeInFrames(
        playout_stream, AAudioStream_getFramesPerBurst(playout_stream));
  } else {
    AAudioStream_setBufferSizeInFrames(playout_stream,
                                       playout_buffer_size_in_bytes / 2);
  }

  if (record_buffer_size_in_bytes == -1) {
    AAudioStream_setBufferSizeInFrames(
        record_stream, AAudioStream_getFramesPerBurst(record_stream));
  } else {
    AAudioStream_setBufferSizeInFrames(record_stream,
                                       record_buffer_size_in_bytes / 2);
  }

  // set the callback data
  cb_data.output_file_descriptor = output_file_descriptor;
  cb_data.record_stream = record_stream;
  cb_data.playout_stream = playout_stream;
  cb_data.end_signal = end_signal_buffer;
  cb_data.end_signal_size_in_frames = end_signal_size_in_bytes / 2;
  cb_data.begin_signal = begin_signal_buffer;
  cb_data.begin_signal_size_in_frames = begin_signal_size_in_bytes / 2;
  cb_data.samplerate = sample_rate;
  cb_data.timeout = timeout;
  cb_data.time_between_signals = time_between_signals;

  LOGI("* Start settings *");
  log_current_settings(playout_stream, record_stream);

  // start the streams
  LOGD("record start stream");
  result = AAudioStream_requestStart(record_stream);
  if (result) {
    LOGD("Failed to create start record stream");
    goto cleanup;
  }
  LOGD("playout start stream");
  result = AAudioStream_requestStart(playout_stream);
  if (result) {
    LOGD("Failed to start playout stream");
    goto cleanup;
  }

  // wait until it is done
  while (running) {
    sleep(1);
  }

  LOGI("playout_xrun: %d", cb_data.pxruns);
  LOGI("record_xrun: %d", cb_data.rxruns);
  LOGI("* Final settings *");
  log_current_settings(playout_stream, record_stream);
  // cleanup
  AAudioStream_requestStop(record_stream);
  AAudioStream_requestStop(playout_stream);

  fclose(output_file_descriptor);

cleanup:
  LOGD("cleanup");
  if (playout_stream) AAudioStream_close(playout_stream);
  if (record_stream) AAudioStream_close(record_stream);
  if (playout_builder) AAudioStreamBuilder_delete(playout_builder);
  if (record_builder) AAudioStreamBuilder_delete(record_builder);
#if __ANDROID_API__ >= 29
  if (midiOutputPort) AMidiOutputPort_close(midiOutputPort);
  if (midiDevice) AMidiDevice_release(midiDevice);
#endif
  return 0;
}
