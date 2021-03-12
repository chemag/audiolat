#!/usr/bin/env python3

import argparse
import numpy as np
import pandas as pd


def find_pairs(data):
    files = pd.unique(data['reference'])

    # The one with most hits is the signal
    signal = None
    max_len = 0
    for fl in files:
        ref_len = len(data.loc[data['reference'] == fl])
        if ref_len > max_len:
            max_len = ref_len
            signal = fl

    matches = []
    input_files = pd.unique(data['input_file'])

    for fl in input_files:
        signals = data.loc[(data['input_file'] == fl) &
                           (data['reference'] == signal)]
        impulses = data.loc[(data['input_file'] == fl) &
                            (data['reference'] != signal)]

        for play_imp in impulses.iterrows():
            closest = None
            min_dist = 1
            time = play_imp[1]['time']
            print(f'{round(time,2)} sec')
            for sig_imp in signals.iterrows():
                dist = time - sig_imp[1]['time']
                if dist > 0 and dist < min_dist:
                    min_dist = dist
                    closest = sig_imp
            if closest is not None:
                matches.append([time, min_dist, fl])
    labels = ['time', 'delay', 'file']
    result = pd.DataFrame.from_records(
        matches, columns=labels, coerce_float=True)
    return result


def main():
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('files', nargs='+', help='file to analyze (csv)')
    parser.add_argument('-o', '--output', default=None)
    parser.add_argument('-l', '--label', default='capt')
    options = parser.parse_args()

    accum_data = None
    for fl in options.files:
        # 135263,16.907875,47,audio.wav

        data = pd.read_csv(fl)
        if data is None:
            exit(0)

        data['input_file'] = fl
        if accum_data is None:
            accum_data = data
        else:
            accum_data = accum_data.append(data)

    data = find_pairs(accum_data)

    input_files = pd.unique(data['file'])

    for fl in input_files:
        average = round(
            np.mean(data.loc[data['file'] == fl]['delay'] * 1000), 2)
        print(f'Average for {fl}: {average} ms')


if __name__ == '__main__':
    main()
