#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>
#include <math.h>
#include <pthread.h>
#include <sys/stat.h>
#include <unistd.h>

#include <string>
//#include <android/trace.h>
#include <dlfcn.h>
#define LOGD(...) \
  __android_log_print(ANDROID_LOG_DEBUG, "audiolat", __VA_ARGS__)

static bool running = false;

struct callback_data {
  FILE *output_file_descriptor;
  AAudioStream *record_stream;
  AAudioStream *playout_stream;
  int16_t *end_signal;
  int end_signal_size;
  int16_t *begin_signal;
  int begin_signal_size;
  int samplerate;
  int timeout;
};

aaudio_data_callback_result_t dataCallback(AAudioStream *stream, void *userData,
                                           void *audioData,
                                           int32_t num_frames) {
  static int written_frames = 0;
  static int playout_num_frames_remaining = 0;
  static int record_num_frames_remaining = 0;
  static float last_ts = 0;

  struct callback_data *cb_data = (struct callback_data *)userData;
  aaudio_stream_state_t playout_state =
      AAudioStream_getState(cb_data->playout_stream);
  float time_sec = (float)written_frames / (float)cb_data->samplerate;

  LOGD(
      "dataCallback type: %s num_frames: %d time_sec: %.2f "
      "playout_num_frames_remaining: %d record_num_frames_remaining: %d",
      (stream == cb_data->record_stream) ? "record" : "playout", num_frames,
      time_sec, playout_num_frames_remaining, record_num_frames_remaining);
  if (stream == cb_data->record_stream) {
    // recording
    written_frames += num_frames;
    if (time_sec - last_ts > 2) {
      // experiment start: we need, as soon as possible, to:
      // 1. play out the end signal
      playout_num_frames_remaining = cb_data->end_signal_size;
      // 2. record the begin signal
      record_num_frames_remaining = cb_data->begin_signal_size;
      // 3. set the time for the next experiment
      last_ts = time_sec;
      LOGD(
          "experiment playout_num_frames_remaining: %d "
          "record_num_frames_remaining: %d",
          playout_num_frames_remaining, record_num_frames_remaining);
    }
    int record_buffer_offset = 0;
    if (record_num_frames_remaining > 0 &&
        record_num_frames_remaining < cb_data->begin_signal_size) {
      // we are in the middle of recording the begin signal:
      // Let's write it on the left side
      // +--------------------------------+
      // |BBBB                            |
      // +--------------------------------+
      int num_frames_to_write =
          std::min(record_num_frames_remaining, num_frames);
      int begin_signal_offset =
          cb_data->begin_signal_size - record_num_frames_remaining;
      LOGD("record source: begin num_frames: %d remaining: %d",
           num_frames_to_write, record_num_frames_remaining);
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
      LOGD("record source: input num_frames: %d", num_frames_to_write);
      fwrite(((int16_t *)audioData) + record_buffer_offset, sizeof(int16_t),
             (size_t)num_frames_to_write, cb_data->output_file_descriptor);
      num_frames -= num_frames_to_write;
    }
    if (record_num_frames_remaining == cb_data->begin_signal_size) {
      // we are at the beginning of recording the begin signal:
      // Let's write it on the right side
      // +--------------------------------+
      // |                            BBBB|
      // +--------------------------------+
      int num_frames_to_write =
          std::min(record_num_frames_remaining, num_frames);
      LOGD("record source: begin num_frames: %d remaining: %d",
           num_frames_to_write, record_num_frames_remaining);
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
    LOGD("playout num_frames: %d time_sec: %.2f", num_frames, time_sec);
    int playout_buffer_offset = 0;
    if ((playout_num_frames_remaining > 0) &&
        (playout_state == AAUDIO_STREAM_STATE_STARTED)) {
      // we are in the middle of playing the end signal:
      // Let's write it on the left side
      // +--------------------------------+
      // |EEEEEEEE                        |
      // +--------------------------------+
      LOGD("playout source: end num_frames: %d remaining: %d", num_frames,
           playout_num_frames_remaining);
      int num_frames_to_write =
          std::min(num_frames, playout_num_frames_remaining);
      int end_signal_offset =
          cb_data->end_signal_size - playout_num_frames_remaining;
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
      LOGD("playout source: silence num_frames: %d", num_frames);
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
  int playout_current_buffer_size =
      AAudioStream_getBufferSizeInFrames(playout_stream);

  int record_frames_per_burst = AAudioStream_getFramesPerBurst(record_stream);
  int record_buffer_capacity =
      AAudioStream_getBufferCapacityInFrames(record_stream);
  int record_current_buffer_size =
      AAudioStream_getBufferSizeInFrames(record_stream);

  LOGD("playout frames_per_burst: %d", playout_frames_per_burst);
  LOGD("playout current_buffer_size: %d", playout_current_buffer_size);
  LOGD("playout buffer_capacity: %d", playout_buffer_capacity);
  LOGD("playout performance_mode: %d",
       AAudioStream_getPerformanceMode(playout_stream));

  LOGD("record frames_per_burst: %d", record_frames_per_burst);
  LOGD("record current_buffer_size: %d", record_current_buffer_size);
  LOGD("record buffer_capacity: %d", record_buffer_capacity);
  LOGD("record performance_mode: %d",
       AAudioStream_getPerformanceMode(record_stream));
}

// main experiment function
extern "C" JNIEXPORT jint JNICALL
Java_com_facebook_audiolat_MainActivity_runAAudio(JNIEnv *env,
                                                  jobject /* this */,
                                                  jobject settings) {
  aaudio_result_t result;
  AAudioStream *playout_stream = nullptr;
  AAudioStream *record_stream = nullptr;
  AAudioStreamBuilder *playout_builder = nullptr;
  AAudioStreamBuilder *record_builder = nullptr;
  int16_t *end_signal_buffer = nullptr;
  int16_t *begin_signal_buffer = nullptr;

  // unpack the test utils
  jclass cSettings = env->GetObjectClass(settings);
  jfieldID fid =
      env->GetFieldID(cSettings, "endSignal", "Ljava/nio/ByteBuffer;");
  jobject end_signal = env->GetObjectField(settings, fid);
  fid = env->GetFieldID(cSettings, "endSignalSize", "I");
  jint end_signal_size = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "beginSignal", "Ljava/nio/ByteBuffer;");
  jobject begin_signal = env->GetObjectField(settings, fid);
  fid = env->GetFieldID(cSettings, "beginSignalSize", "I");
  jint begin_signal_size = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "sampleRate", "I");
  jint sample_rate = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "deviceId", "I");
  jint device_id = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "outputFilePath", "Ljava/lang/String;");
  jstring output_file_path = (jstring)env->GetObjectField(settings, fid);
  fid = env->GetFieldID(cSettings, "timeout", "I");
  jint timeout = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "playoutBufferSize", "I");
  jint playout_buffer_size = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "recordBufferSize", "I");
  jint record_buffer_size = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "usage", "I");
  jint usage = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "timeBetweenSignals", "I");
  jint time_between_signals = env->GetIntField(settings, fid);

  running = true;

  struct callback_data cb_data;
  memset(&cb_data, 0, sizeof(struct callback_data));
  LOGD("** SAMPLE RATE == %d **", sample_rate);

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
  AAudioStreamBuilder_setDeviceId(playout_builder, AAUDIO_UNSPECIFIED);
  AAudioStreamBuilder_setUsage(playout_builder, usage);
  AAudioStreamBuilder_setDirection(playout_builder, AAUDIO_DIRECTION_OUTPUT);
  // AAUDIO_SHARING_MODE_EXCLUSIVE no available
  AAudioStreamBuilder_setSharingMode(playout_builder,
                                     AAUDIO_SHARING_MODE_SHARED);
  AAudioStreamBuilder_setSampleRate(playout_builder, sample_rate);
  AAudioStreamBuilder_setChannelCount(playout_builder, 1);
  AAudioStreamBuilder_setFormat(playout_builder, AAUDIO_FORMAT_PCM_I16);
  AAudioStreamBuilder_setBufferCapacityInFrames(playout_builder, 960);
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
  AAudioStreamBuilder_setDeviceId(record_builder, device_id);
  AAudioStreamBuilder_setDirection(record_builder, AAUDIO_DIRECTION_INPUT);
  // AAUDIO_SHARING_MODE_EXCLUSIVE no available
  AAudioStreamBuilder_setSharingMode(record_builder,
                                     AAUDIO_SHARING_MODE_SHARED);
  AAudioStreamBuilder_setSampleRate(record_builder, sample_rate);
  AAudioStreamBuilder_setChannelCount(record_builder, 1);
  AAudioStreamBuilder_setFormat(record_builder, AAUDIO_FORMAT_PCM_I16);
  AAudioStreamBuilder_setPerformanceMode(record_builder,
                                         AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  AAudioStreamBuilder_setBufferCapacityInFrames(record_builder, 64);
  AAudioStreamBuilder_setInputPreset(record_builder,
                                     AAUDIO_INPUT_PRESET_UNPROCESSED);
  AAudioStreamBuilder_setDataCallback(record_builder, dataCallback, &cb_data);

  // open both streams
  result = AAudioStreamBuilder_openStream(playout_builder, &playout_stream);
  LOGD("Open playout stream: %d", result);
  if (result) {
    LOGD("Failed to create open playout stream");
    goto cleanup;
  }
  result = AAudioStreamBuilder_openStream(record_builder, &record_stream);
  LOGD("Open record stream: %d", result);
  if (result) {
    LOGD("Failed to create open record stream");
    goto cleanup;
  }

  // set stream sizes
  AAudioStream_setBufferSizeInFrames(playout_stream, playout_buffer_size);
  AAudioStream_setBufferSizeInFrames(record_stream, record_buffer_size);

  // set the callback data
  cb_data.output_file_descriptor = output_file_descriptor;
  cb_data.record_stream = record_stream;
  cb_data.playout_stream = playout_stream;
  cb_data.end_signal = end_signal_buffer;
  cb_data.end_signal_size = end_signal_size;
  cb_data.begin_signal = begin_signal_buffer;
  cb_data.begin_signal_size = begin_signal_size;
  cb_data.samplerate = sample_rate;
  cb_data.timeout = timeout;

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
    sleep(time_between_signals);
  }

  // cleanup
  AAudioStream_requestStop(record_stream);
  AAudioStream_requestStop(playout_stream);

  fclose(output_file_descriptor);

cleanup:
  LOGD("Cleanup");
  if (playout_stream) AAudioStream_close(playout_stream);
  if (record_stream) AAudioStream_close(record_stream);
  if (playout_builder) AAudioStreamBuilder_delete(playout_builder);
  if (record_builder) AAudioStreamBuilder_delete(record_builder);
  return 0;
}
