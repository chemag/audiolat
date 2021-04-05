#!/bin/bash


function capture_logcat {
    adb logcat -c
    adb logcat -v threadtime > ${1}&
    sid=($!)
    trap "kill ${sid[@]}" INT
}

samplerate=$1
timeout=$2
playbuff=$3
recbuff=$4
api=$5
signal=$6

sdk_version=$(adb shell getprop ro.build.version.sdk)

# <device>.aaudio.aec_on.agc_on.ns_on.cond_on.exp_<id>.csv
# <device>.aaudio.aec_off.agc_off.ns_off.cond_on.exp_<id>.csv
test_id=$7 #.aaudio.aec_off.agc_off.ns_off.cond_on.exp_<id>
midi=$8

script=${BASH_SOURCE[0]}
sdir=${script%/*}
wdir=$(pwd)
if [[ $sdk_version -ge 29 ]]; then
    files=$(adb shell ls /storage/emulated/0/Android/data/com.facebook.audiolat/files/audiolat*.raw)
else
    files=$(adb shell ls /storage/emulated/0/Android/data/com.facebook.audiolat.sdk28/files/audiolat*.raw)
fi

for file in ${files}; do
    adb shell rm "${file}"
done
capture_logcat audiolat_tmp.txt

# -e midi ${midi}
#PERFORMANCE_MODE_LOW_LATENCY 1
testtype=""
if [ -z ${midi} ]; then
    testtype="systemfull"
    if [[ $sdk_version -ge 29 ]]; then
        adb shell am start -S -n com.facebook.audiolat/.MainActivity -e sr "${samplerate}" -e t "${timeout}" -e rbs "${playbuff}" -e pbs "${recbuff}" -e api "${api}" -e signal "${signal}" -e usage 14 -e atpm 1
    else
        adb shell am start -S -n com.facebook.audiolat.sdk28/com.facebook.audiolat.MainActivity -e sr "${samplerate}" -e t "${timeout}" -e rbs "${playbuff}" -e pbs "${recbuff}" -e api "${api}" -e signal "${signal}" -e usage 14 -e atpm 1
    fi
else
    testtype="systemdown"
    if [[ $sdk_version -ge 29 ]]; then
        adb shell am start -S -n com.facebook.audiolat/.MainActivity -e sr "${samplerate}" -e t "${timeout}" -e rbs "${playbuff}" -e pbs "${recbuff}" -e api "${api}" -e signal "${signal}" -e usage 14 -e atpm 1 -e midi 1 -e midiid ${midi}
    else
        adb shell am start -S -n com.facebook.audiolat.sdk28/com.facebook.audiolat.MainActivity -e sr "${samplerate}" -e t "${timeout}" -e rbs "${playbuff}" -e pbs "${recbuff}" -e api "${api}" -e signal "${signal}" -e usage 14 -e atpm 1 -e midi 1 -e midiid ${midi}
    fi
fi
sleep "${timeout}"
# give me some startup margin
sleep 1
logfile="${wdir}/audiolat_${samplerate}Hz_${playbuff}_${recbuff}.logcat"
match_log="${wdir}/audiolat_${samplerate}Hz_match_times.log"
cp audiolat_tmp.txt ${logfile}
rm audiolat_tmp.txt

if [[ $sdk_version -ge 29 ]]; then
    files=$(adb shell ls /storage/emulated/0/Android/data/com.facebook.audiolat/files/audiolat*.raw)
else
    files=$(adb shell ls /storage/emulated/0/Android/data/com.facebook.audiolat.sdk28/files/audiolat*.raw)
fi

file=${files[0]}
adb pull "${file}"
input=${file##*/}
output=${file##*/}.wav
test_full_name=${testtype}.${test_id}
echo "Full name is ${test_full_name}"

ffmpeg -f s16le -ar "${samplerate}" -i "${input}" -ar "${samplerate}"  "${output}" -y

begin_signal="${sdir}/../audio/begin_signal.wav"
end_signal="${input#*audiolat_}"
end_signal="${sdir}/../audio/${end_signal%*.raw}.wav"

if [ -z ${midi} ]; then
    "${sdir}/find_pulses.py" "${begin_signal}" "${end_signal}" -i "${output}" --limit_marker 0.03 -t 80,50
    "${sdir}/calc_delay.py"  "${output}.csv" -o "${wdir}/${test_id}.match.csv" > "${match_log}"
fi
device=$("${sdir}/parse_data.sh" "${logfile}" "${match_log}" "${samplerate}" "${test_full_name}.settings.csv")

echo "Change names"
echo "mv ${output} ${test_full_name}.wav"
mv ${output} ${device}.${test_full_name}.wav
mv ${output}.csv ${device}.${test_full_name}.csv
mv ${logfile} ${device}.${test_full_name}.logcat

if ! [ -z ${midi} ]; then
    find_transient.py -m "${end_signal}" -t 50 --limit_marker 0.03 "${device}.${test_full_name}.wav" 
else
    mv "${wdir}/${test_id}.match.csv" "${wdir}/${device}.${test_full_name}.match.csv"
fi
rm ${input}
