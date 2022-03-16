#!/usr/bin/env python3

import argparse
import numpy as np
import pandas as pd
debug = 0
MIN_DIST_SEC = 0  # 0.01


def find_pairs(data):
    # The fist one is the "begin" signal
    begin_filename = data.iloc[0]['reference']
    matches = []

    begin_signals = data.loc[data['reference'] == begin_filename]
    end_signals = data.loc[data['reference'] != begin_filename]
    for end_signal in end_signals.iterrows():
        closest_begin_signal = None
        min_dist_sec = 1
        time = end_signal[1]['time']
        if debug > 0:
            print(f'end_time_sec: {round(time,3)}')
        for begin_signal in begin_signals.iterrows():
            dist_sec = time - begin_signal[1]['time']
            if dist_sec > MIN_DIST_SEC and dist_sec < min_dist_sec:
                min_dist_sec = dist_sec
                closest_begin_signal = begin_signal
        if closest_begin_signal is not None:
            matches.append([time, min_dist_sec])

    labels = ['timestamp', 'latency']
    result = pd.DataFrame.from_records(
        matches, columns=labels, coerce_float=True)
    return result


def parse_input_file(filename, output):
    # 135263,16.907875,47,audio.wav
    data = pd.read_csv(filename, header=0)
    if data is None:
        print(f'warning: No data on {filename}')
        return None, None, None
    pairs = find_pairs(data)
    if output is not None:
        pairs.to_csv(output, index=False)
    samples = len(pairs['latency'])
    if samples == 0:
        print(f'warning: no pairs {pairs}')
        return None, None, 0
    average_sec = np.mean(pairs['latency'])
    stddev_sec = np.std(pairs['latency'])
    return average_sec, stddev_sec, samples


def main():
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('files', nargs='+', help='file to analyze (csv)')
    parser.add_argument('-o', '--output', default=None)
    parser.add_argument('-l', '--label', default='capt')
    options = parser.parse_args()

    for filename in options.files:
        average_sec, stddev_sec, samples = parse_input_file(
            filename, options.output)
        if average_sec is None:
            continue
        print(f'\n***\nfilename: {filename}\naverage roundtrip delay: {round(average_sec, 3)} sec'
              f', stddev: {round(stddev_sec, 3)} sec\nnumbers of samples collected: {samples}\n***')


if __name__ == '__main__':
    main()
