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
  __android_log_print(ANDROID_LOG_DEBUG, "latencycheck", __VA_ARGS__)

static bool running = false;

struct callback_data {
  FILE *file;
  AAudioStream *rec_stream;
  AAudioStream *play_stream;
  int16_t *pattern;
  int size;
  int16_t *start_signal;
  int start_signal_size;
  int samplerate;
  int timeout;
};

aaudio_data_callback_result_t dataCallback(AAudioStream *stream, void *userData,
                                           void *audioData,
                                           int32_t num_frames) {
  static int written_frames = 0;
  static int buffer_index = 0;
  static bool playing = false;
  static float last_ts = 0;

  struct callback_data *cb_data = (struct callback_data *)userData;
  aaudio_stream_state_t play_state =
      AAudioStream_getState(cb_data->play_stream);
  float time_sec = (float)written_frames / (float)cb_data->samplerate;

  if (stream == cb_data->rec_stream) {
    // recording
    if (time_sec - last_ts > 2) {
      playing = true;
      LOGD("Rec Write start signal");
      fwrite((int16_t *)audioData, sizeof(int16_t),
             (size_t)num_frames - cb_data->start_signal_size, cb_data->file);
      fwrite(cb_data->start_signal, (size_t)cb_data->start_signal_size,
             sizeof(int16_t), cb_data->file);
      buffer_index = 0;
      last_ts = time_sec;
    } else {
      LOGD("Rec Write data - %d, time: %.2f sec", num_frames, time_sec);
      fwrite(audioData, sizeof(int16_t), (size_t)num_frames, cb_data->file);
    }

    written_frames += num_frames;
    LOGD("Rec update written frames: %d", written_frames);
    if (time_sec > cb_data->timeout) {
      running = false;
    }
  } else {
    if (playing && play_state == AAUDIO_STREAM_STATE_STARTED &&
        buffer_index + num_frames < cb_data->size) {
      LOGD("Play Write chirp -%d", num_frames);
      memcpy(audioData, cb_data->pattern + buffer_index,
             sizeof(int16_t) * num_frames);
      buffer_index += num_frames;
    } else {
      LOGD("Play Write silence - %d, time: %.2f sec", num_frames, time_sec);
      int16_t *zeros = (int16_t *)malloc(sizeof(int16_t) * num_frames);
      memset(zeros, 0, sizeof(int16_t) * num_frames);
      memcpy(audioData, zeros, sizeof(int16_t) * num_frames);
      free(zeros);
      playing = false;
    }
  }

  return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_facebook_latencycheck_MainActivity_runAAudio(JNIEnv *env,
                                                      jobject /* this */,
                                                      jobject settings) {
  jclass cSettings = env->GetObjectClass(settings);
  jfieldID fid =
      env->GetFieldID(cSettings, "reference", "Ljava/nio/ByteBuffer;");
  jobject reference = env->GetObjectField(settings, fid);
  fid = env->GetFieldID(cSettings, "refSize", "I");
  jint ref_size = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "startSignal", "Ljava/nio/ByteBuffer;");
  jobject start_signal = env->GetObjectField(settings, fid);
  fid = env->GetFieldID(cSettings, "startSignalSize", "I");
  jint start_signal_size = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "sampleRate", "I");
  jint sample_rate = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "deviceId", "I");
  jint device_id = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "recPath", "Ljava/lang/String;");
  jstring rec_path = (jstring)env->GetObjectField(settings, fid);
  fid = env->GetFieldID(cSettings, "timeout", "I");
  jint timeout = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "playBufferSize", "I");
  jint play_buffer_size = env->GetIntField(settings, fid);
  fid = env->GetFieldID(cSettings, "recBufferSize", "I");
  jint rec_buffer_size = env->GetIntField(settings, fid);

  struct callback_data cb_data;

  running = true;
  memset(&cb_data, 0, sizeof(struct callback_data));
  LOGD("** SAMPLE RATE == %d **", sample_rate);

  AAudioStreamBuilder *play_builder = NULL;
  AAudioStreamBuilder *rec_builder = NULL;
  AAudioStream *play_stream = NULL;
  AAudioStream *rec_stream = NULL;
  aaudio_result_t result;

  struct stat file_info;
  FILE *rawfile = NULL;
  int16_t *pattern = NULL;
  int16_t *start_signal_impulse = NULL;

  const char *filename = env->GetStringUTFChars(rec_path, 0);
  LOGD("Open file: %s", filename);
  rawfile = fopen(filename, "wb");
  if (rawfile == NULL) {
    LOGD("Failed to open file");
    goto cleanup;
  }

  pattern = (int16_t *)env->GetDirectBufferAddress(reference);
  if (!pattern) {
    LOGD("Failed to get direct buffer");
    return -1;
  }

  start_signal_impulse = (int16_t *)env->GetDirectBufferAddress(start_signal);
  if (!start_signal_impulse) {
    LOGD("Failed to get direct buffer");
    return -1;
  }

  // --- audio player
  result = AAudio_createStreamBuilder(&play_builder);
  LOGD("Create play stream: %d", result);
  if (result) {
    LOGD("Failed to create play stream");
    goto cleanup;
  }
  AAudioStreamBuilder_setDeviceId(
      play_builder, AAUDIO_UNSPECIFIED);  // more of this later...device_id);
  AAudioStreamBuilder_setDirection(play_builder, AAUDIO_DIRECTION_OUTPUT);
  AAudioStreamBuilder_setSharingMode(
      play_builder, AAUDIO_SHARING_MODE_SHARED);  // AAUDIO_SHARING_MODE_EXCLUSIVE
                                                  // no available
  AAudioStreamBuilder_setSampleRate(play_builder, sample_rate);
  AAudioStreamBuilder_setChannelCount(play_builder, 1);
  AAudioStreamBuilder_setFormat(play_builder, AAUDIO_FORMAT_PCM_I16);
  AAudioStreamBuilder_setBufferCapacityInFrames(play_builder, 960);
  AAudioStreamBuilder_setPerformanceMode(play_builder,
                                         AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  AAudioStreamBuilder_setDataCallback(play_builder, dataCallback, &cb_data);

  // --- audio recorder
  result = AAudio_createStreamBuilder(&rec_builder);
  LOGD("Create rec stream: %d", result);
  if (result) {
    LOGD("Failed to create rec stream");
    goto cleanup;
  }
  AAudioStreamBuilder_setDeviceId(rec_builder, device_id);
  AAudioStreamBuilder_setDirection(rec_builder, AAUDIO_DIRECTION_INPUT);
  AAudioStreamBuilder_setSharingMode(
      rec_builder, AAUDIO_SHARING_MODE_SHARED);  // AAUDIO_SHARING_MODE_EXCLUSIVE
                                                 // no available
  AAudioStreamBuilder_setSampleRate(rec_builder, sample_rate);
  AAudioStreamBuilder_setChannelCount(rec_builder, 1);
  AAudioStreamBuilder_setFormat(rec_builder, AAUDIO_FORMAT_PCM_I16);
  AAudioStreamBuilder_setPerformanceMode(rec_builder,
                                         AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  AAudioStreamBuilder_setBufferCapacityInFrames(rec_builder, 64);
  AAudioStreamBuilder_setInputPreset(rec_builder,
                                     AAUDIO_INPUT_PRESET_UNPROCESSED);
  AAudioStreamBuilder_setDataCallback(rec_builder, dataCallback, &cb_data);

  // Streams
  result = AAudioStreamBuilder_openStream(play_builder, &play_stream);
  LOGD("Open play stream: %d", result);
  if (result) {
    LOGD("Failed to create open play stream");
    goto cleanup;
  }

  result = AAudioStreamBuilder_openStream(rec_builder, &rec_stream);
  LOGD("Open rec stream: %d", result);
  if (result) {
    LOGD("Failed to create open rec stream");
    goto cleanup;
  }

  AAudioStream_setBufferSizeInFrames(play_stream, play_buffer_size);
  AAudioStream_setBufferSizeInFrames(rec_stream, rec_buffer_size);
  cb_data.file = rawfile;
  cb_data.rec_stream = rec_stream;
  cb_data.play_stream = play_stream;
  cb_data.pattern = pattern;
  cb_data.size = ref_size;
  cb_data.start_signal = start_signal_impulse;
  cb_data.start_signal_size = start_signal_size;
  cb_data.samplerate = sample_rate;
  cb_data.timeout = timeout;

  // print current settings
  {
    int play_frame_per_burst = AAudioStream_getFramesPerBurst(play_stream);
    int play_buffer_capacity =
        AAudioStream_getBufferCapacityInFrames(play_stream);
    int play_current_buffer_size =
        AAudioStream_getBufferSizeInFrames(play_stream);

    int rec_frame_per_burst = AAudioStream_getFramesPerBurst(rec_stream);
    int rec_buffer_capacity =
        AAudioStream_getBufferCapacityInFrames(rec_stream);
    int rec_current_buffer_size =
        AAudioStream_getBufferSizeInFrames(rec_stream);

    LOGD("Play frame per burst: %d", play_frame_per_burst);
    LOGD("Play current_buffer_size: %d", play_current_buffer_size);
    LOGD("Play buffer_capacity: %d", play_buffer_capacity);
    LOGD("Play Performance mode: %d",
         AAudioStream_getPerformanceMode(play_stream));

    LOGD("Rec frame per burst: %d", rec_frame_per_burst);
    LOGD("Rec current_buffer_size: %d", rec_current_buffer_size);
    LOGD("Rec buffer_capacity: %d", rec_buffer_capacity);
    LOGD("Rec Performance mode: %d",
         AAudioStream_getPerformanceMode(rec_stream));
  }
  LOGD("Rec Start stream");
  result = AAudioStream_requestStart(rec_stream);
  if (result) {
    LOGD("Failed to create start rec stream");
    goto cleanup;
  }
  LOGD("Play Start stream");
  result = AAudioStream_requestStart(play_stream);
  if (result) {
    LOGD("Failed to start play stream");
    goto cleanup;
  }

  while (running) {
    sleep(2);
  }

  AAudioStream_requestStop(rec_stream);
  AAudioStream_requestStop(play_stream);

  stat(filename, &file_info);
  fclose(rawfile);

cleanup:
  LOGD("Cleanup");
  if (play_stream) AAudioStream_close(play_stream);
  if (rec_stream) AAudioStream_close(rec_stream);
  if (play_builder) AAudioStreamBuilder_delete(play_builder);
  if (rec_builder) AAudioStreamBuilder_delete(rec_builder);
  return 0;
}
