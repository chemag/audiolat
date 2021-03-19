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

# <device>.aaudio.aec_on.agc_on.ns_on.cond_on.exp_<id>.csv 
# <device>.aaudio.aec_off.agc_off.ns_off.cond_on.exp_<id>.csv
test_id=$7 #.aaudio.aec_off.agc_off.ns_off.cond_on.exp_<id>

script=${BASH_SOURCE[0]}
sdir=${script%/*}
wdir=$(pwd)
adb shell am force-stop com.facebook.audiolat
files=$(adb shell ls /sdcard/audiolat*.raw)
for file in ${files}; do
    adb shell rm "${file}"
done
capture_logcat audiolat_tmp.txt
#PERFORMANCE_MODE_LOW_LATENCY 1
adb shell am start -n com.facebook.audiolat/.MainActivity -e sr "${samplerate}" -e t "${timeout}" -e rbs "${playbuff}" -e pbs "${recbuff}" -e api "${api}" -e signal "${signal}" -e usage 2 -e atm 1
sleep "${timeout}"
# give me some startup margin
sleep 1
logfile="${wdir}/audiolat_${samplerate}Hz_${playbuff}_${recbuff}.logcat"
match_log="${wdir}/audiolat_${samplerate}Hz_match_times.log"
cp audiolat_tmp.txt ${logfile}
rm audiolat_tmp.txt
files=$(adb shell ls /sdcard/audiolat*.raw)

file=${files[0]}
adb pull "${file}"
input=${file##*/}
output=${file##*/}.wav
ffmpeg -f s16le -ar "${samplerate}" -i "${input}" -ar "${samplerate}"  "${output}" -y

begin_signal="${sdir}/../audio/begin_signal.wav"
end_signal="${input#*audiolat_}"
end_signal="${sdir}/../audio/${end_signal%*.raw}.wav"

echo "find pulses"
"${sdir}/find_pulses.py" "${begin_signal}" "${end_signal}" -i "${output}" -sr "${samplerate}" -t 35

echo "calc delays"
"${sdir}/calc_delay.py"  "${output}.csv" -o "${wdir}/${test_id}.match.csv" > "${match_log}"

echo "parse data to ${test_id}.mean.csv"
echo "${sdir}/parse_data.sh ${logfile} ${match_log} ${sampelrate} ${wdir}/${test_id}.mean.csv"
device=$("${sdir}/parse_data.sh" "${logfile}" "${match_log}" "${sampelrate}" "${test_id}.mean.csv")
echo "Change names"
mv ${output} ${device}.${test_id}.wav
mv ${output}.csv ${device}.${test_id}.csv
mv ${logfile} ${device}.${test_id}.logcat
mv "${wdir}/${test_id}.match.csv" "${wdir}/${device}.${test_id}.match.csv"
rm ${input}


