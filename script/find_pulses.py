#!/usr/bin/env python3
# (c) Facebook, Inc. and its affiliates. Confidential and proprietary.

import soundfile as sf
import sys
import scipy.signal as sig
import pandas as pd
import argparse
import matplotlib.pyplot as plt
import numpy as np


def match_buffers(data, ref_data, threshold=95, gain=0):
    """
        Tries to find ref_data in data using correlation measurement.
    """
    size = len(ref_data)

    max_cc = 0
    max_index = -1
    max_time = -1

    if gain != 0:
        data = np.multiply(data, gain)
    corr = np.correlate(data, ref_data)

    val = max(corr)
    index = np.where(corr == val)[0][0]
    cc = np.corrcoef(data[index: index + size], ref_data)[1, 0] * 100
    if np.isnan(cc):
        cc = 0
    return index, int(cc)


def main():
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('files', nargs='+', help='file(s) to analyze (video)')
    parser.add_argument('-i', '--input',  required=True)
    parser.add_argument('-t', '--threshold', type=int, default=50)
    parser.add_argument('-sr', '--samplerate', type=int, default=48000)
    options = parser.parse_args()

    dist_name = options.input
    dist_data = []

    if dist_name[-3:] == "wav":
        dist = sf.SoundFile(dist_name, "r")
        dist_data = dist.read()
    else:
        print("Only wav file supported")
        exit(0)
    last = 0

    split_times = []
    for ref_name in options.files:
        print(f"** Check for {ref_name}")
        template = []
        ref_data = []

        if ref_name[-3:] == "wav":
            ref = sf.SoundFile(ref_name, "r")
            ref_data = ref.read()
        else:
            print("Only wav file supported")
            exit(0)

        template = ref_data
        max_pos = 0
        ts = 0

        read_len = int(1.5 * options.samplerate)
        counter = 0
        last = 0
        print(
            f"calc, dist data len = {len(dist_data)}, template len = {len(template)}")

        while last <= len(dist_data) - len(template):
            index, cc = match_buffers(dist_data[last:last + read_len],
                                      template)

            index += last
            pos = index - max_pos
            if pos < 0:
                pos = 0
            if cc > options.threshold:
                time = pos / options.samplerate
                print(f"Append: {pos} @ {round(time, 2)} s, cc: {cc}")
                split_times.append([pos, time, cc, ref_name])

            last += read_len  # len(template)
            counter += 1

        data = pd.DataFrame()
        labels = ['sample', 'time', 'correlation', 'reference']
        data = pd.DataFrame.from_records(
            split_times, columns=labels, coerce_float=True)

    data.to_csv(dist_name + ".csv", index=False)


if __name__ == '__main__':
    main()
