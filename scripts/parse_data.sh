logfile=${1}
match_log=${2}
samplerate=${3}
name=${4}
average=$(cat "${match_log}"| grep -Po "[0-9.]*(?= ms)")

playout_current_buffer_size=$(cat ${logfile} | grep -Po "(?<=playout current_buffer_size: )[0-9]*" | tail -1 )
playout_buffer_capacity=$(cat ${logfile} | grep -Po "(?<=playout buffer_capacity: )[0-9]*" | tail -1)
playout_frames_per_burst=$(cat ${logfile} | grep -Po "(?<=playout frames_per_burst: )[0-9]*" | tail -1)
playout_performance_mode=$(cat ${logfile} | grep -Po "(?<=playout performance_mode: )[0-9]*" | tail -1 )


record_current_buffer_size=$(cat ${logfile} | grep -Po "(?<=record current_buffer_size: )[0-9]*" | tail -1)
record_buffer_capacity=$(cat ${logfile} | grep -Po "(?<=record current_buffer_size: )[0-9]*" | tail -1)
record_frames_per_burst=$(cat ${logfile} | grep -Po "(?<=record frames_per_burst: )[0-9]*" | tail -1)
record_performance_mode=$(cat ${logfile} | grep -Po "(?<=record performance_mode: )[0-9]*" | tail -1)

platform=$(adb shell getprop ro.boot.hardware.platform)
product=$(adb shell getprop ro.build.product)
build=$(adb shell getprop ro.build.id)
hwmaj=$(adb shell getprop ro.boot.hardware.majorid)
hwmin=$(adb shell getprop ro.boot.hardware.minorid)

header="product, platform, revision, build, samplerate, latency ms, playout buffer size, playout buffer capacity,playout frames per burst, playout performance mode,  record buffer size, record buffer capacity, record frames per burst, record performance mode"
data="${product},${platform},${hwmaj}.${hwmin},${build},${samplerate},${average},${playout_current_buffer_size},${playout_buffer_capacity},${playout_frames_per_burst},${playout_performance_mode},${record_current_buffer_size},${record_buffer_capacity},${record_frames_per_burst},${record_performance_mode}"


output="${product}.${4}"

echo ${header} > ${output}
echo ${data} >> ${output}
echo "${product}"
