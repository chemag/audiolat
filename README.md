# latencycheck
Measure output->input delay in android system.


## 1. Prerequisites

* adb connection to the device being tested.
* ffmpeg with decoding support for the codecs to be tested
* android sdk setup and environment variables set
* android ndk

## 2. Operation

(1) set up the android SDK and NDK in the `local.properties` file.

Create a `local.properties` file with valid entries for the `ndk.dir` and
`sdk.dir` variables.

```
> $ cat local.properties
> ndk.dir: /opt/android_ndk/android-ndk-r19/
> sdk.dir: /opt/android_sdk/
```

Note that this file should not be added to the repo.

(2) build the encapp app

```
> $ ./gradlew clean
> $ ./gradlew build
> $ ./gradlew installDebug

or install the build release apk at:
app/build/outputs/apk/release/

If now permission dialoges appears you have to set the permissions in the app menu of Android settings.

(3) Run the app

Run the app using adb:
>$ adb shell am start -n com.facebook.latencycheck/.MainActivity 

or with extended settings:
>$ adb shell am start -n com.facebooklatencycheck/.MainActivity  -e sr SAMPLE_RATE -e t TEST_LENGTH_SECS -e rbs REC_BUFFER_SIZE_SAMPLES -e pbs PLAY_BUFFER_SIZE_SAMPLES

(4) Analyse result
* The recorded file can be found in /sdcard/lc_capture*.raw
* Pull the file and convert to pcm s16 formated wav file
* Run 
> $ python3 find_pulses.py SIGNAL_WAV CHIRP_SAMPLERATE -i RECORDER_FILE -sr SAMPLE_RATE -t 20
'-t 20' will trigger on a badly damaged signal.

or

* run the bash script:
> $ run_test.sh SAMPLERATE TEST_LENGTH_SECS REC_BUFFER_SIZE_SAMPLES PLAY_BUFFER_SIZE_SAMPLES

Finally
* Run the output csv file from previous step:
> $ python3 calc_delay.py RECORDER_FILE.CSV

 


