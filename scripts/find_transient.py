#!/usr/bin/env python3
# (c) Facebook, Inc. and its affiliates. Confidential and proprietary.
import os
import soundfile as sf
import pandas as pd
import argparse
import numpy as np
import common as cm


def find_transients_raw(audiopath, samplerate, marker, test_label, threshold=50):
    noisy = sf.SoundFile(audiopath, 'r', format='RAW', samplerate=int(samplerate),
                         channels=1, subtype='PCM_16', endian='FILE')
    return find_transients(noisy, marker, test_label, threshold)


def find_transients(audiofile, marker, test_label, threshold=50, limit_marker=-1, verbose=False, peak_span=18, max_distance=0.5, leading=False):

    total_max = -100
    rms = -100

    rms, peak_level, crest, bias = cm.audio_levels(audiofile)
    gain = cm.dBToFloat(-6)/cm.dBToFloat(peak_level[0])
    print(f"gain         = {gain}")
    blocksize = int(0.5 * audiofile.samplerate)
    audiofile.seek(0)
    audio_data = audiofile.read() * gain
    ref_data = []
    if marker[-3:] == 'wav':
        if os.path.exists(marker):
            ref = sf.SoundFile(marker, 'r')
            ref_data = ref.read()
            if len(ref_data) <= 0:
                print(f"{marker} zero length, check path")
                exit(0)
        else:
            print(f"{marker} not found, check path")
            exit(0)

    else:
        print('Only wav file supported')
        exit(0)
    if limit_marker > 0:
        ref_data = ref_data[:int(limit_marker * audiofile.samplerate)]
    max_pos = 0
    markers = cm.find_markers(ref_data, audio_data,
                              threshold=threshold,
                              samplerate=audiofile.samplerate,
                              verbose=verbose)

    index = 0
    triggered = False
    threshold = peak_level[0] - peak_span
    # remove click
    print(str(audio_data[audio_data >= 1]))
    audio_data[audio_data >= 1] = 0
    split_times = []
    while index + blocksize < len(audio_data):
        data = audio_data[index:index + blocksize]
        local_max = np.argmax(data)
        local_max_db = cm.floatToDB(data[local_max])
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

    print(f"Audio peaks:\n{data}")
    print(f"Identified markers:\n{markers}")
    match = []
    for point in markers.iterrows():
        time = point[1]['time']
        if leading:
            # limit to .5 second and 10ms in the future
            signals = data.loc[(data['time'] > time + 0.010) &
                               (data['time'] < time + max_distance)]
        else:
            # limit to .5 seconds and 5ms in the past
            signals = data.loc[(data['time'] < time - 0.005) &
                               (data['time'] > time - max_distance)]
        if len(signals) > 0:
            end = signals.iloc[0]
            match.append([
                round(end['time'], 3),
                abs(round(end['time'] - time, 3)),
                test_label,
                round(end['local max level'], 2),
                round(end['file max level'], 2),
                round(end['rms'], 2),
            ])

    labels = ['timestamp', 'latency', 'file', 'local max level',
              'file max level', 'rms']
    matches = pd.DataFrame.from_records(
        match, columns=labels, coerce_float=True)
    return matches


def main():
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('files', nargs='+', help='file(s) to analyze (video)')
    parser.add_argument('-m', '--marker')
    parser.add_argument('-l', '--leading', type=bool, default=False)
    parser.add_argument('-t', '--threshold', type=int, default=90)
    parser.add_argument('-p', '--peak_span', type=int, default=18)
    parser.add_argument('--limit_marker', type=float, default=-1)
    parser.add_argument('-d', '--max_distance', type=int, default=.5)
    parser.add_argument('-v', '--verbose', required=False, action='store_true')

    options = parser.parse_args()

    for input_name in options.files:
        print(f'** Check {input_name}')
        if input_name[-3:] == 'wav':
            audiofile = sf.SoundFile(input_name, 'r')
        else:
            print('Only wav file supported')
            exit(0)

        data = find_transients(audiofile, options.marker, input_name,
                               threshold=options.threshold,
                               limit_marker=options.limit_marker,
                               peak_span=options.peak_span,
                               max_distance=options.max_distance)
        data.to_csv(input_name + '.peaks_match.csv', index=False)

        print(f"{data}")


if __name__ == '__main__':
    main()
