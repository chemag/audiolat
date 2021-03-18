#!/usr/bin/env python3

import argparse
import numpy as np
import pandas as pd


# minimum full audio latency distance
MIN_DIST_SEC = 0.002


def find_pairs(data, debug=0):

    # The one with most hits is the "begin" signal
    begin_filename = None
    max_len = 0
    for signal_filename in pd.unique(data['reference']):
        ref_len = len(data.loc[data['reference'] == signal_filename])
        if ref_len > max_len:
            max_len = ref_len
            begin_filename = signal_filename

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


def parse_input_file(filename):
    # 135263,16.907875,47,audio.wav
    data = pd.read_csv(filename)
    if data is None:
        print('warning: No data on {filename}')
        return None, None, None
    pairs = find_pairs(data)
    samples = len(pairs['latency'])
    if samples == 0:
        print('warning: No data on {filename}')
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
        average_sec, stddev_sec, samples = parse_input_file(filename)
        if average_sec is None:
            continue
        print(f'filename: {filename} average_sec: {average_sec} '
              f'stddev_sec: {stddev_sec} samples: {samples}')


if __name__ == '__main__':
    main()
