#!/usr/bin/env python3
# (c) Facebook, Inc. and its affiliates. Confidential and proprietary.
import os
import subprocess
import soundfile as sf
import pandas as pd
import argparse
import numpy as np
import matplotlib.pyplot as plt
from math import log10
import time

serial = ""
DUT_FILE_PATH = ''
MAIN_ACTIVITY = ''
APPNAME_MAIN = ''

def checkVersion():
    global DUT_FILE_PATH
    global MAIN_ACTIVITY
    global APPNAME_MAIN
    adb_cmd = f'adb {serial} shell cmd package list packages | grep -oE com.facebook.audiolat.*'
    ret, stdout, stderr = run_cmd(adb_cmd, False)
    print(f'ret = {ret}, std ={stdout}')
    name=stdout.rstrip('\n')
    APPNAME_MAIN = name
    # com.facebook.audiolat.sdk28/com.facebook.audiolat.MainActivity
    MAIN_ACTIVITY = f'{name}/com.facebook.audiolat.MainActivity'
    DUT_FILE_PATH = f'/storage/emulated/0/Android/data/{name}'

    print(f'main activity: {MAIN_ACTIVITY}')
    print(f'file path: {DUT_FILE_PATH}')
    return APPNAME_MAIN, MAIN_ACTIVITY, DUT_FILE_PATH

def run_cmd(cmd, debug=0):
    ret = True
    try:
        if debug > 0:
            print(cmd, sep=' ')
        process = subprocess.Popen(cmd, shell=True,
                                   stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE)
        stdout, stderr = process.communicate()
    except Exception:
        ret = False
        print('Failed to run command: ' + cmd)

    return ret, stdout.decode(), stderr.decode()


def wait_for_exit(serial, debug=0):
    adb_cmd = f'adb {serial} shell pidof {APPNAME_MAIN}'
    pid = -1
    current = 1
    while (current != -1):
        if pid == -1:
            ret, stdout, stderr = run_cmd(adb_cmd, False)
            pid = -1
            if len(stdout) > 0:
                pid = int(stdout)
        time.sleep(1)
        ret, stdout, stderr = run_cmd(adb_cmd, debug)
        current = -2
        if len(stdout) > 0:
            try:
                current = int(stdout)
            except Exception:
                print(f'wait for exit caught exception: {stdout}')
                continue
        else:
            current = -1


def build_args(settings):
    print(f'Settings\n{settings}')
    ret = ""
    if not isinstance(settings['content_type'], type(None)):
        ret = f"{ret} -e ctype {settings['content_type']} "
    if not isinstance(settings['usage'], type(None)):
        ret = f"{ret} -e usage {settings['usage']} "
    if not isinstance(settings['input_preset'], type(None)):
        ret = f"{ret} -e iprst {settings['input_preset']} "
    # -e pbs 32 -e usage 14 -e atpm 1'
    return ret

def floatToDB(val):
    """
    Calculates the dB values from a floating point representation
    ranging between -1.0 to 1.0 where 1.0 is 0 dB
    """
    if val <= 0:
        return -100.0
    else:
        return 20.0 * log10(val)


def dBToFloat(val):
    """"
    Calculates a float value ranging from -1.0 to 1.0
    Where 1.0 is 0dB
    """
    return 10 ** (val / 20.0)


def audio_levels_raw(audiopath, samplerate, start=0, end=-1):
    audio = sf.SoundFile(audiopath, 'r', format='RAW', samplerate=int(samplerate),
                         channels=1, subtype='PCM_16', endian='FILE')

    return audio_levels(audio, start, end)


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


def visualize_corr(data, ref_data, corr):
    fig, (ax_data, ax_ref_data, ax_corr) = plt.subplots(
        3, 1, figsize=(4.8, 4.8))
    ax_data.plot(ref_data)
    ax_data.set_title('Ref impulse')
    ax_data.set_xlabel('Sample Number')
    ax_ref_data.plot(data)
    ax_ref_data.set_title('Signal with noise')
    ax_ref_data.set_xlabel('Sample Number')
    ax_corr.plot(corr)
    ax_corr.set_title('Cross-correlated signal')
    ax_corr.set_xlabel('Lag')
    ax_data.margins(0, 0.1)
    ax_ref_data.margins(0, 0.1)
    ax_corr.margins(0, 0.1)
    fig.tight_layout()
    plt.show()


def match_buffers(data, ref_data, gain=0, verbose=False):
    """
        Tries to find ref_data in data using correlation measurement.
    """
    global options
    size = len(ref_data)

    if gain != 0:
        data = np.multiply(data, gain)
    corr = np.correlate(data, ref_data)
    val = max(corr)

    index = np.where(corr == val)[0][0]

    cc = np.corrcoef(data[index: index + size], ref_data)[1, 0] * 100
    if np.isnan(cc):
        cc = 0
    if verbose:
        print(f"{val} @ {index}, cc = {cc}")
        visualize_corr(data, ref_data, corr)
    return index, int(cc)


def find_markers(reference, noisy, threshold, samplerate, verbose=False):
    # Sets how close we can find multiple matches, 100ms
    window = int(0.1 * samplerate)
    max_pos = 0

    total_length = len(noisy) - len(reference)
    read_len = int(len(reference) + window)
    counter = 0
    last = 0
    split_times = []
    while last <= total_length:
        print('{:2d}%'.format(int(round(100.0*last/total_length, 2))),
              end="\r", flush=True)
        index, cc = match_buffers(noisy[last:last + read_len],
                                  reference, verbose=verbose)

        index += last
        pos = index - max_pos
        if pos < 0:
            pos = 0
        if cc > threshold:
            time = pos / samplerate
            if verbose:
                print(f'Append: {pos} @ {round(time, 2)} s, cc: {cc}')
            split_times.append([pos, time, cc])
        last += window
        counter += 1

    data = pd.DataFrame()
    labels = ['sample', 'time', 'correlation']
    data = pd.DataFrame.from_records(
        split_times, columns=labels, coerce_float=True)

    return data
