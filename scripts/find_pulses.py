#!/usr/bin/env python3
# (c) Facebook, Inc. and its affiliates. Confidential and proprietary.

import soundfile as sf
import pandas as pd
import argparse
import numpy as np
import matplotlib.pyplot as plt
import common as cm

verbose = False


def find_pulses_raw_input(reference_path, noisy_path, samplerate, threshold, limit_length=-1):
    #format = {'format':'RAW', 'samplerate':samplerate, 'channels':1, 'subtype':'PCM_16', 'endian':'FILE'}
    print(
        f'Find pulses ref = {reference_path} in {noisy_path}, sr = {samplerate}')
    noisy = sf.SoundFile(noisy_path, 'r', format='RAW', samplerate=int(samplerate),
                         channels=1, subtype='PCM_16', endian='FILE')
    return find_pulses_sf(reference_path, noisy, noisy_path, threshold, limit_length)


def find_pulses(reference_path, noisy_path, threshold, limit_length=-1):
    noisy = sf.SoundFile(noisy_path, 'r')
    return find_pulses_sf(reference_path, noisy, noisy_path, threshold, limit_length)


def find_pulses_sf(reference_path, noisy_soundfile, noisy_name, threshold, limit_length=-1):
    global verbose
    noisy_data = noisy_soundfile.read()
    template = []
    ref_data = []
    last = 0
    accum_data = None
    if reference_path[-3:] == 'wav':
        ref = sf.SoundFile(reference_path, 'r')
        ref_data = ref.read()
    else:
        print('Only wav file supported')
        exit(0)
    if limit_length > 0:
        ref_data = ref_data[:int(limit_length * noisy_soundfile.samplerate)]
    print(f"threshold = {threshold}")
    data = cm.find_markers(ref_data, noisy_data, threshold,
                           noisy_soundfile.samplerate, verbose=verbose)
    data['reference'] = reference_path

    return data


def main():
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('files', nargs='+', help='file(s) to analyze (video)')
    parser.add_argument('-i', '--input',  required=True)
    parser.add_argument('-t', '--threshold', default="50", type=float)
    parser.add_argument('--limit_marker', type=float, default=-1)
    parser.add_argument('-v', '--verbose', required=False, action='store_true')
    global options
    global verbose
    options = parser.parse_args()
    verbose = options.verbose
    if options.input[-3:] != 'wav':
        print('Only wav file supported')
        exit(0)

    data = []
    for ref_name in options.files:
        print(f'** Check for {ref_name}')
        data.append(find_pulses(ref_name, options.input, options.threshold))

    conc = pd.concat(data)

    conc = conc.sort_values(by=['time'])
    conc.to_csv(options.input + '.csv', index=False)


if __name__ == '__main__':
    main()
