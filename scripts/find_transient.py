#!/usr/bin/env python3
# (c) Facebook, Inc. and its affiliates. Confidential and proprietary.

import soundfile as sf
import pandas as pd
import argparse
import numpy as np
import common as cm

def main():
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('files', nargs='+', help='file(s) to analyze (video)')
    parser.add_argument('-m', '--marker')
    parser.add_argument('-l', '--leading', type=bool, default=False)
    parser.add_argument('-t', '--threshold', type=int, default=90)
    parser.add_argument('-p', '--peak_span', type=int, default=18)
    parser.add_argument('--limit_marker', type=float, default=-1)
    parser.add_argument('-v', '--verbose', required=False, action='store_true')

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

        rms, peak_level, crest, bias = cm.audio_levels(audiofile)
        gain = cm.dBToFloat(-6)/cm.dBToFloat(peak_level[0])
        print(f"gain         = {gain}")
        blocksize = int(0.5 * audiofile.samplerate)
        audiofile.seek(0)
        audio_data = audiofile.read() * gain
        ref_data = []
        if options.marker[-3:] == 'wav':
            ref = sf.SoundFile(options.marker, 'r')
            ref_data = ref.read()
        else:
            print('Only wav file supported')
            exit(0)
        if options.limit_marker:
            ref_data = ref_data[:int(options.limit_marker * audiofile.samplerate)]
        max_pos = 0
        markers = cm.find_markers(ref_data, audio_data, threshold=options.threshold, sampelrate=audiofile.samplerate, verbose=options.verbose)

        index = 0
        triggered = False
        threshold = peak_level[0] - options.peak_span
        # remove click
        print(str(audio_data[audio_data >= 1]))
        audio_data[audio_data >= 1] = 0

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
                # limit to one second and 5ms in the past
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
