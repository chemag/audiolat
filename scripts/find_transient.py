#!/usr/bin/env python3
# (c) Facebook, Inc. and its affiliates. Confidential and proprietary.

import soundfile as sf
import pandas as pd
import argparse
import numpy as np
from math import log10


def floatToDB(val):
    """
    Calculates the dB values from a floating point representation
    ranging between -1.0 to 1.0 where 1.0 is 0 dB
    """
    if val <= 0:
        return -100.0
    else:
        return 20.0 * log10(val)


def audio_levels(audiofile, start=0, end=-1):
    """
        Calculates rms and max peak level in dB
    """

    blocksize = audiofile.channels * audiofile.samplerate * 10
    peak_level = [0] * audiofile.channels
    rms = [0] * audiofile.channels
    peak = [0] * audiofile.channels
    total_level = [0] * audiofile.channels
    crest = [0] * audiofile.channels
    bias = [0] * audiofile.channels
    block_counter = 0
    audiofile.seek(start)

    while audiofile.tell() < audiofile.frames:
        data = audiofile.read(blocksize)
        for channel in range(0, audiofile.channels):
            if audiofile.channels == 1:
                data_ = data
            else:
                data_ = data[:, channel]
            total_level[channel] += np.sum(data_)
            rms[channel] += np.mean(np.square(data_))
            peak[channel] = max(abs(data_))
            if (peak[channel] > peak_level[channel]):
                peak_level[channel] = peak[channel]
        block_counter += 1

    for channel in range(0, audiofile.channels):
        rms[channel] = np.sqrt(rms[channel] / block_counter)
        crest[channel] = round(floatToDB(peak_level[channel] / rms[channel]),
                               2)
        bias[channel] = round(floatToDB(total_level[channel] /
                              (block_counter * 10 * audiofile.samplerate)), 2)
        rms[channel] = round(floatToDB(rms[channel]), 2)
        peak_level[channel] = round(floatToDB(peak_level[channel]), 2)

    return rms, peak_level, crest, bias


def match_buffers(data, ref_data, threshold=95, gain=0):
    """
        Tries to find ref_data in data using correlation measurement.
    """
    size = len(ref_data)

    if gain != 0:
        data = np.multiply(data, gain)
    corr = np.correlate(data, ref_data)

    val = max(corr)
    index = np.where(corr == val)[0][0]
    cc = np.corrcoef(data[index: index + size], ref_data)[1, 0] * 100
    if np.isnan(cc):
        cc = 0
    return index, int(cc)


def find_markers(options, dist_data, sampelrate):
    template = []
    if options.marker[-3:] == 'wav':
        ref = sf.SoundFile(options.marker, 'r')
        template = ref.read()
    else:
        print('Only wav file supported')
        exit(0)

    max_pos = 0

    read_len = int(1.5 * sampelrate)
    counter = 0
    last = 0
    split_times = []
    while last <= len(dist_data) - len(template):
        index, cc = match_buffers(dist_data[last:last + read_len],
                                  template)

        index += last
        pos = index - max_pos
        if pos < 0:
            pos = 0
        if cc > options.threshold:
            time = pos / sampelrate
            split_times.append([pos, time, cc])

        last += read_len  # len(template)
        counter += 1

    data = pd.DataFrame()
    labels = ['sample', 'time', 'correlation']
    data = pd.DataFrame.from_records(
        split_times, columns=labels, coerce_float=True)
    return data


def main():
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('files', nargs='+', help='file(s) to analyze (video)')
    parser.add_argument('-m', '--marker')
    parser.add_argument('-l', '--leading', type=bool, default=False)
    parser.add_argument('-t', '--threshold', type=int, default=90)

    options = parser.parse_args()

    total_max = -100
    rms = -100

    for input_name in options.files:
        print(f'** Check {input_name}')
        split_times = []
        if input_name[-3:] == 'wav':
            audiofile = sf.SoundFile(input_name, 'r')
        else:
            print('Only wav file supported')
            exit(0)

        rms, peak_level, crest, bias = audio_levels(audiofile)
        blocksize = int(0.5 * audiofile.samplerate)
        audiofile.seek(0)
        audio_data = audiofile.read()
        markers = find_markers(options, audio_data, audiofile.samplerate)
        index = 0
        triggered = False

        threshold = peak_level[0] - 6
        # remove click
        print(str(audio_data[audio_data >= 1]))
        audio_data[audio_data >= 1] = 0

        while index + blocksize < len(audio_data):
            data = audio_data[index:index + blocksize]
            local_max = np.argmax(data)
            local_max_db = floatToDB(data[local_max])
            if (local_max_db >= threshold) and not triggered:
                triggered = True
                split_times.append([index + local_max,
                                    (index + local_max) / audiofile.samplerate,
                                    local_max_db])
            elif (local_max_db < threshold - 6) and triggered:
                triggered = False
            if local_max_db > total_max:
                total_max = local_max_db
            index += int(blocksize/8)

        data = pd.DataFrame()
        labels = ['sample', 'time', 'local max level']
        data = pd.DataFrame.from_records(
            split_times, columns=labels, coerce_float=True)
        data['file max level'] = total_max
        data['rms'] = rms[0]

        print(f"{data}")
        print(f"{markers}")
        match = []
        for point in markers.iterrows():
            time = point[1]['time']
            if options.leading:
                # limit to one second and 10ms in the future
                signals = data.loc[(data['time'] > time + 0.010) &
                                   (data['time'] < time + 1)]
            else:
                # limit to one second and 10ms in the past
                signals = data.loc[(data['time'] < time - 0.005) &
                                   (data['time'] > time - 1)]
            if len(signals) > 0:
                end = signals.iloc[0]
                match.append([
                    round(end['time'], 2),
                    abs(round(end['time'] - time, 2)),
                    input_name,
                    round(end['local max level'], 2),
                    round(end['file max level'], 2),
                    round(end['rms'], 2),
                ])

        labels = ['time', 'delay', 'file', 'local max level',
                  'file max level', 'rms']
        matches = pd.DataFrame.from_records(
            match, columns=labels, coerce_float=True)
        matches.to_csv(input_name + '.peaks_match.csv', index=False)

        print(f"{matches}")


if __name__ == '__main__':
    main()
