#!/usr/bin/env python3
import os
import subprocess
import argparse
import time
import re
import os
import find_pulses as fp
import calc_delay as cd
import pandas as pd
import common as cm
from common import run_cmd
from common import serial
from common import DUT_FILE_PATH
from common import MAIN_ACTIVITY
from common import APPNAME_MAIN


debug = True
AUDIOLAT_OUTPUT_FILE_NAME_RE = r'audiolat*.raw'
SCRIPT_PATH = os.path.realpath(__file__)
SCRIPT_DIR, _ = os.path.split(SCRIPT_PATH)
ROOT_DIR, _ = os.path.split(SCRIPT_DIR)
REF_DIR = f'{ROOT_DIR}/audio'


def measure(samplerate, api, usb, output, label, settings):
    print(f'Measure using {APPNAME_MAIN}, activiy: {MAIN_ACTIVITY}')
    print(f'File at : {DUT_FILE_PATH}')
    prefix = f'{api}_'
    if usb:
        prefix = f'{prefix}usb_'
    short_rate = int(samplerate/1000)

    # cleanup
    adb_cmd = f'adb {serial} shell rm {DUT_FILE_PATH}/files/*.raw'
    ret, stdout, stderr = run_cmd(adb_cmd, debug)

    adb_cmd = f'adb {serial} shell am force-stop {APPNAME_MAIN}'
    ret, stdout, stderr = run_cmd(adb_cmd, debug)
    args = f'-e api {api} -e sr {samplerate} {cm.build_args(settings)} '
    if usb:
        args += ' -e usb-input true -e usb-output true'
    adb_cmd = f'adb {serial} shell am start -n {MAIN_ACTIVITY} {args}'
    ret, stdout, stderr = run_cmd(adb_cmd, debug)

    time.sleep(10)
    # Collect result
    cm.wait_for_exit(serial)
    adb_cmd = f'adb {serial} shell ls {DUT_FILE_PATH}/files/*.raw'
    ret, stdout, stderr = run_cmd(adb_cmd, True)
    output_files = [stdout]

    workdir = output
    if len(output_files) == 0:
        exit(0)

    if not os.path.exists(workdir):
        os.mkdir(workdir)
    sub_dir = '_'.join([workdir, 'files'])
    output_dir = f'{workdir}/{sub_dir}/'
    run_cmd(f'mkdir {output_dir}')
    result_json = []
    local_files = []
    for file in output_files:
        if file == '':
            print('No file found')
            continue
        # pull the output file
        base_file_name = os.path.basename(file).strip()

        name = f'{output_dir}/{prefix}_{base_file_name}'
        local_files.append(f'{name}')

        adb_cmd = f'adb {serial} pull {file.strip()} {name}'
        run_cmd(adb_cmd, debug)

    results = []
    start_signal = f'{REF_DIR}/begin_signal.wav'
    chirp = f'{REF_DIR}/chirp_{short_rate}k_300ms.wav'
    threshold = int(settings['threshold'])
    for file in local_files:
        if not os.path.exists(file):
            print('File does not exits - fix it :) ')
            exit(0)

        rms, peak_level, crest, bias = cm.audio_levels_raw(file, samplerate)
        output_name = f'{file}_start.csv'
        start_signals = fp.find_pulses_raw_input(
            start_signal, file, samplerate, 90)
        output_name = f'{file}_signal.csv'
        print(f'look for {chirp}')
        chirp_signals = fp.find_pulses_raw_input(chirp, file, samplerate, threshold)

        tmp = [start_signals, chirp_signals]
        data = pd.concat(tmp)
        name = f'{workdir}/signals_{prefix}_{samplerate}.csv'
        data.to_csv(name, index=False)
        filename = f'{workdir}/results_{prefix}_{samplerate}.csv'
        average_sec, stddev_sec, samples = cd.parse_input_file(name, filename)
        if average_sec is None:
            continue
        print(f'\n***\nfilename: {filename}\naverage roundtrip delay: {round(average_sec, 3)} sec'
              f', stddev: {round(stddev_sec, 3)} sec\nnumbers of samples collected: {samples}\n***')



def main():
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('-o', '--output', default='latency')
    parser.add_argument('-l', '--label', default='capture')
    parser.add_argument('-s', '--serial', default='')
    parser.add_argument('-a', '--api', default='aaudio')
    parser.add_argument('-r', '--rates', default='48000')
    parser.add_argument('-g', '--gaindB', default='-32')
    parser.add_argument('-t', '--threshold', default='20')
    parser.add_argument('--usage', type=int, default=None)
    parser.add_argument('--content_type', type=int, default=None)
    parser.add_argument('--input_preset', type=int, default=None,)
    parser.add_argument('--usb',  action='store_true',)
    options = parser.parse_args()

    global serial
    global DUT_FILE_PATH
    global MAIN_ACTIVITY
    global APPNAME_MAIN
    if len(options.serial) > 0:
        serial = f'-s {options.serial}'

    APPNAME_MAIN, MAIN_ACTIVITY, DUT_FILE_PATH = cm.checkVersion()
    settings = {'content_type': options.content_type,
                'usage': options.usage,
                'input_preset': options.input_preset,
                'gaindB': options.gaindB,
                'threshold': options.threshold}

    rates = []
    apis = []

    for rate in options.rates.split(','):
        rates.append(int(rate))
    for api in options.api.split(','):
        apis.append(api)


    for api in apis:
        for rate in rates:
            measure(rate, api, options.usb, options.output,
                    f'{options.label}', settings)



if __name__ == '__main__':
    main()
