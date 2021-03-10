#!/bin/bash
samplerate=$1
timeout=$2
playbuff=$3
recbuff=$3
script=${BASH_SOURCE[0]}
sdir=${script%/*}

adb shell am force-stop com.facebook.latencycheck
files=$(adb shell ls /sdcard/lc_capture*.raw)
for file in ${files}; do
    adb shell rm "${file}"
done
adb shell am start -n com.facebook.latencycheck/.MainActivity -e sr "${samplerate}" -e t "${timeout}" -e rbs "${playbuff}" -e pbs "${recbuff}"
sleep "${timeout}"
# give me some startup margin
sleep 3
files=$(adb shell ls /sdcard/lc_capture*.raw)
for file in ${files}; do
    adb pull "${file}"
    input=${file##*/}
    output=${file##*/}.wav
    ffmpeg -f s16le -ar "${samplerate}" -i "${input}" -ar "${samplerate}"  "${output}" -y
    ref="${sdir}/../audio/chirp2_48k_300ms.wav"

    if [[ ${samplerate} -eq 16000 ]];then
        ref=${sdir}/../audio/chirp2_16k_300ms.wav
    elif [[ ${samplerate} -eq 8000 ]];then
        ref="${sdir}/../audio/chirp2_8k_300ms.wav"
    fi
    echo "Find pulse"
    "${sdir}/find_pulses.py" "${sdir}/../audio/start_signal.wav" "${ref}" -i "${output}" -sr "${samplerate}" -t 20

done
    

