#include <android/log.h>
#include <jni.h>
#include <math.h>
#include <oboe/Oboe.h>
#include <pthread.h>
#include <sys/stat.h>
#include <unistd.h>

#include <string>
//#include <android/trace.h>
#include <dlfcn.h>
#define LOGD(...) \
  __android_log_print(ANDROID_LOG_DEBUG, "audiolat", __VA_ARGS__)

static bool running = false;
static long last_midi_nanotime = -1;

struct callback_data {
  FILE *output_file_descriptor;
  int16_t *end_signal;
  int end_signal_size_in_frames;
  int16_t *begin_signal;
  int begin_signal_size_in_frames;
  int samplerate;
  int timeout;
  int time_between_signals;
};

class AudioCallback : public oboe::AudioStreamCallback {
  struct callback_data *cb_data;

 public:
  void setData(callback_data *data) { cb_data = data; }

  oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream,
                                        void *audioData, int32_t num_frames) {
    static int written_frames = 0;
    static int playout_num_frames_remaining = 0;
    static int record_num_frames_remaining = 0;
    static float last_ts = 0;
    float time_sec = (float)written_frames / (float)cb_data->samplerate;

    LOGD(
        "dataCallback type: %s num_frames: %d time_sec: %.2f "
        "playout_num_frames_remaining: %d record_num_frames_remaining: %d",
        (stream->getDirection() == oboe::Direction::Input) ? "record"
                                                           : "playout",
        num_frames, time_sec, playout_num_frames_remaining,
        record_num_frames_remaining);
    if (stream->getDirection() == oboe::Direction::Input) {
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
        LOGD(
            "experiment playout_num_frames_remaining: %d "
            "record_num_frames_remaining: %d",
            playout_num_frames_remaining, record_num_frames_remaining);
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
      if (record_num_frames_remaining == cb_data->begin_signal_size_in_frames) {
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
      if (last_midi_nanotime > 0 && record_num_frames_remaining <= 0) {
        playout_num_frames_remaining = cb_data->end_signal_size_in_frames;
        LOGD("Set play frame rem to : %d", playout_num_frames_remaining);
        struct timespec time;
        clock_gettime(CLOCK_MONOTONIC, &time);
        long current_nanotime = time.tv_sec * 1000000000 + time.tv_nsec;
        if (record_num_frames_remaining > 0) {
          LOGD(
              "playout midi triggered but we are still playing "
              "last_midi_nanotime: %ld "
              "current_nanotime: %ld "
              "difference: %ld",
              last_midi_nanotime, current_nanotime,
              (current_nanotime - last_midi_nanotime));
        } else
          LOGD(
              "playout midi triggered in full "
              "last_midi_nanotime: %ld "
              "current_nanotime: %ld "
              "difference: %ld",
              last_midi_nanotime, current_nanotime,
              (current_nanotime - last_midi_nanotime));
        last_midi_nanotime = -1;
      }
      if (playout_num_frames_remaining > 0) {
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
        LOGD("playout source: silence num_frames: %d", num_frames);
        memset((void *)(((int16_t *)audioData) + playout_buffer_offset), 0,
               sizeof(int16_t) * num_frames);
      }
    }

    return oboe::DataCallbackResult::Continue;
  }
};

void log_current_settings(oboe::AudioStream *playout_stream,
                          oboe::AudioStream *record_stream) {
  // print current settings
  LOGD("playout frames_per_burst: %d", playout_stream->getFramesPerBurst());
  LOGD("playout current_buffer_size_in_frames: %d",
       playout_stream->getBufferCapacityInFrames());
  LOGD("playout buffer_capacity: %d", playout_stream->getBufferSizeInFrames());
  LOGD("playout performance_mode: %d", playout_stream->getPerformanceMode());

  LOGD("record frames_per_burst: %d", record_stream->getFramesPerBurst());
  LOGD("record current_buffer_size_in_frames: %d",
       record_stream->getBufferSizeInFrames());
  LOGD("record buffer_capacity: %d",
       record_stream->getBufferCapacityInFrames());
  LOGD("record performance_mode: %d", record_stream->getPerformanceMode());
}

extern "C" JNIEXPORT void JNICALL
Java_com_facebook_audiolat_MainActivity_oboeMidiSignal(JNIEnv *env,
                                                       jobject /* this */,
                                                       jlong nanotime) {
  last_midi_nanotime = nanotime;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_facebook_audiolat_MainActivity_runOboe(JNIEnv *env, jobject /* this */,
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
  fid = env->GetFieldID(cSettings, "deviceId", "I");
  jint device_id = env->GetIntField(settings, fid);
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
  fid = env->GetFieldID(cSettings, "timeBetweenSignals", "I");
  jint time_between_signals = env->GetIntField(settings, fid);

  running = true;

  struct callback_data cb_data;
  memset(&cb_data, 0, sizeof(struct callback_data));
  LOGD("** SAMPLE RATE == %d **", sample_rate);

  oboe::AudioStreamBuilder builder;
  std::shared_ptr<oboe::AudioStream> playout_stream = nullptr;
  std::shared_ptr<oboe::AudioStream> record_stream = nullptr;
  AudioCallback audioCallback;
  oboe::Result result;
  int16_t *end_signal_buffer = nullptr;
  int16_t *begin_signal_buffer = nullptr;

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
  // builder.setDeviceId(AAUDIO_UNSPECIFIED);  // more of this
  // later...device_id);
  builder.setDirection(oboe::Direction::Output);
  builder.setSharingMode(oboe::SharingMode::Shared);
  builder.setSampleRate(sample_rate);
  builder.setChannelCount(oboe::ChannelCount::Mono);
  builder.setFormat(oboe::AudioFormat::I16);
  builder.setBufferCapacityInFrames(960);
  builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
  builder.setUsage(oboe::Usage::VoiceCommunication);
  audioCallback.setData(&cb_data);
  builder.setCallback(&audioCallback);

  result = builder.openStream(playout_stream);
  LOGD("Open play stream: %d, audio api: %d", result,
       playout_stream->getAudioApi());
  if (result != oboe::Result::OK) {
    LOGD("Failed to create open play stream");
    goto cleanup;
  }

  // set up the record (uplink, mic) audio stream
  builder.setDirection(oboe::Direction::Input);
  builder.setInputPreset(oboe::InputPreset::Unprocessed);
  builder.setBufferCapacityInFrames(64);

  builder.setCallback(&audioCallback);

  result = builder.openStream(record_stream);
  LOGD("Open rec stream: %d, audio api: %d", result,
       record_stream->getAudioApi());
  if (result != oboe::Result::OK) {
    LOGD("Failed to create open rec stream");
    goto cleanup;
  }

  playout_stream->setBufferSizeInFrames(playout_buffer_size_in_bytes / 2);
  record_stream->setBufferSizeInFrames(record_buffer_size_in_bytes / 2);
  log_current_settings(&(*playout_stream), &(*record_stream));
  // set the callback data
  cb_data.output_file_descriptor = output_file_descriptor;
  cb_data.end_signal = end_signal_buffer;
  cb_data.end_signal_size_in_frames = end_signal_size_in_bytes / 2;
  cb_data.begin_signal = begin_signal_buffer;
  cb_data.begin_signal_size_in_frames = begin_signal_size_in_bytes / 2;
  cb_data.samplerate = sample_rate;
  cb_data.timeout = timeout;
  cb_data.time_between_signals = time_between_signals;

  // start the streams
  LOGD("Rec Start stream");
  result = record_stream->requestStart();
  if (result != oboe::Result::OK) {
    LOGD("Failed to create start rec stream");
    goto cleanup;
  }
  LOGD("Play Start stream");
  result = playout_stream->requestStart();
  if (result != oboe::Result::OK) {
    LOGD("Failed to start play stream");
    goto cleanup;
  }

  while (running) {
    sleep(2);
  }

  record_stream->requestStop();
  playout_stream->requestStop();
  fclose(output_file_descriptor);

cleanup:
  LOGD("Cleanup");
  if (playout_stream) playout_stream->close();
  if (record_stream) record_stream->close();

  return 0;
}
