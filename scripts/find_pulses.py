#!/usr/bin/env python3
# (c) Facebook, Inc. and its affiliates. Confidential and proprietary.

import soundfile as sf
import pandas as pd
import argparse
import numpy as np
import matplotlib.pyplot as plt
import common as cm

def main():
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('files', nargs='+', help='file(s) to analyze (video)')
    parser.add_argument('-i', '--input',  required=True)
    parser.add_argument('-t', '--threshold', default="50")
    parser.add_argument('--limit_marker', type=float, default=-1)
    parser.add_argument('-v', '--verbose', required=False, action='store_true')
    global options
    options = parser.parse_args()

    noisy_name = options.input
    noisy_data = []

    if noisy_name[-3:] == 'wav':
        noisy = sf.SoundFile(noisy_name, 'r')
        noisy_data = noisy.read()
    else:
        print('Only wav file supported')
        exit(0)
    last = 0

    thresholds = options.threshold.split(",")
    threshold_counter = 0
    threshold = int(thresholds[threshold_counter])

    accum_data = None
    for ref_name in options.files:
        print(f'** Check for {ref_name}')
        template = []
        ref_data = []

        if ref_name[-3:] == 'wav':
            ref = sf.SoundFile(ref_name, 'r')
            ref_data = ref.read()
        else:
            print('Only wav file supported')
            exit(0)
        if options.limit_marker:
            ref_data = ref_data[:int(options.limit_marker * noisy.samplerate)]
        data = cm.find_markers(ref_data, noisy_data, threshold, noisy.samplerate, verbose=options.verbose)
        data['reference'] = ref_name
        if threshold_counter < len(thresholds) - 1:
            threshold_counter += 1
            threshold = int(thresholds[threshold_counter])
        if accum_data is None:
            accum_data = data
        else:
            accum_data = accum_data.append(data)
    accum_data.to_csv(noisy_name + '.csv', index=False)


if __name__ == '__main__':
    main()
