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
  int16_t *zeros;
  int zeros_size;
  int samplerate;
  int timeout;
};

aaudio_data_callback_result_t dataCallback(AAudioStream *stream, void *userData,
                                           void *audioData,
                                           int32_t num_frames) {
  static int written_frames = 0;
  static int play_buffer_index = 0;
  static int rec_buffer_index = 0;
  static float last_ts = 0;


  struct callback_data *cb_data = (struct callback_data *)userData;
  aaudio_stream_state_t playout_state =
      AAudioStream_getState(cb_data->playout_stream);
  float time_sec = (float)written_frames / (float)cb_data->samplerate;

  if (stream == cb_data->record_stream) {
    LOGD("record num_frames: %d time_sec: %.2f", num_frames, time_sec);
    // recording
    if (time_sec - last_ts > 2 || rec_buffer_index > 0) {
      size_t signal_size = (num_frames >  cb_data->begin_signal_size - rec_buffer_index)?
                            cb_data->begin_signal_size - rec_buffer_index: num_frames;

      LOGD("record write: begin signal num_frames: %d", num_frames);
      if (rec_buffer_index > 0) {
        //Write tail
        fwrite(cb_data->begin_signal + rec_buffer_index, signal_size,
            sizeof(int16_t), cb_data->output_file_descriptor);
        fwrite((int16_t *)audioData, sizeof(int16_t),
            (size_t)num_frames - signal_size, cb_data->output_file_descriptor);
      } else {
        //Write the beginning of the signal, the recorded data to be written could be 0
        fwrite((int16_t *)audioData, sizeof(int16_t),
            (size_t)num_frames - signal_size, cb_data->output_file_descriptor);
        fwrite(cb_data->begin_signal + rec_buffer_index, signal_size,
            sizeof(int16_t), cb_data->output_file_descriptor);
      }

      rec_buffer_index += signal_size;
      if (rec_buffer_index >= cb_data->begin_signal_size) {
        rec_buffer_index = 0;
      }
      play_buffer_index = 0;
      last_ts = time_sec;
    } else {
      fwrite(audioData, sizeof(int16_t), (size_t)num_frames, cb_data->output_file_descriptor);
    }

    written_frames += num_frames;
    if (time_sec > cb_data->timeout) {
      running = false;
    }
  } else {
    if (num_frames > cb_data->zeros_size) {
      if (cb_data->zeros) {
         free(cb_data->zeros);
      }
      cb_data->zeros = (int16_t *)malloc(sizeof(int16_t) * num_frames);
      cb_data->zeros_size = num_frames;
      memset(cb_data->zeros, 0, sizeof(int16_t) * num_frames);
    }

    if (playout_state == AAUDIO_STREAM_STATE_STARTED &&
        cb_data->end_signal_size - play_buffer_index > 0) {
      size_t signal_size = (num_frames >  cb_data->end_signal_size - play_buffer_index)?
                  cb_data->end_signal_size - play_buffer_index: num_frames;

      LOGD("playout source: num_frames: %d,buffer index = %d", num_frames, play_buffer_index);
      memcpy(audioData, cb_data->end_signal + play_buffer_index,
             sizeof(int16_t) * signal_size);
      play_buffer_index += signal_size;

      int rem = num_frames - signal_size;
      // if more space is available, write silence
      if (rem > 0) {
         memcpy(audioData, cb_data->zeros, sizeof(int16_t) * rem);
      }
    } else {
      memcpy(audioData, cb_data->zeros, sizeof(int16_t) * num_frames);
    }
  }

  return AAUDIO_CALLBACK_RESULT_CONTINUE;
}


void log_current_settings(AAudioStream *playout_stream, AAudioStream *record_stream) {
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
  // more of this later...device_id);
  AAudioStreamBuilder_setDeviceId(playout_builder, AAUDIO_UNSPECIFIED);
  AAudioStreamBuilder_setDirection(playout_builder, AAUDIO_DIRECTION_OUTPUT);
  // AAUDIO_SHARING_MODE_EXCLUSIVE no available
  AAudioStreamBuilder_setSharingMode(playout_builder, AAUDIO_SHARING_MODE_SHARED);
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
  AAudioStreamBuilder_setSharingMode(record_builder, AAUDIO_SHARING_MODE_SHARED);
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
    sleep(2);
  }

  // cleanup
  AAudioStream_requestStop(record_stream);
  AAudioStream_requestStop(playout_stream);

  struct stat file_info;
  stat(output_file_name, &file_info);
  fclose(output_file_descriptor);

cleanup:
  LOGD("Cleanup");
  if (playout_stream) AAudioStream_close(playout_stream);
  if (record_stream) AAudioStream_close(record_stream);
  if (playout_builder) AAudioStreamBuilder_delete(playout_builder);
  if (record_builder) AAudioStreamBuilder_delete(record_builder);
  if (cb_data.zeros) {
       free(cb_data.zeros);
  }
  return 0;
}
