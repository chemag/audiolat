#!/usr/bin/env python3
import os
import subprocess
import argparse
import time
import re
import os
import find_pulses as fp
import find_transient as ft
import calc_delay as cd
import pandas as pd
import common as cm


global serial
serial = ""
debug = True
AUDIOLAT_OUTPUT_FILE_NAME_RE = r'audiolat*.raw'
APPNAME_MAIN = 'com.facebook.audiolat'
SCRIPT_PATH = os.path.realpath(__file__)
SCRIPT_DIR, _ = os.path.split(SCRIPT_PATH)
ROOT_DIR, _ = os.path.split(SCRIPT_DIR)
REF_DIR = f'{ROOT_DIR}/audio'


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
    adb_cmd = f'adb -s {serial} shell pidof {APPNAME_MAIN}'
    pid = -1
    current = 1
    while (current != -1):
        if pid == -1:
            ret, stdout, stderr = run_cmd(adb_cmd, debug)
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
    # -e pbs 32 -e usage 14 -e atpm 1 -e midiid xx'
    return ""


def measure(samplerate, api, usb, midiid, output, label, settings):
    global serial
    prefix = f'{api}_'
    if usb:
        prefix = f'{prefix}usb_'
    short_rate = int(samplerate/1000)

    # cleanup
    adb_cmd = f'adb {serial} shell rm /storage/emulated/0/Android/data/com.facebook.audiolat/files/*.raw'
    ret, stdout, stderr = run_cmd(adb_cmd, debug)

    adb_cmd = f'adb {serial} shell am force-stop com.facebook.audiolat'
    ret, stdout, stderr = run_cmd(adb_cmd, debug)
    args = f'-e api {api} -e sr {samplerate} -e midi 1 {build_args(settings)}'
    if usb:
        args += ' -e usb-input true -e usb-output true'
    adb_cmd = f'adb {serial} shell am start -n com.facebook.audiolat/.MainActivity {args}'
    ret, stdout, stderr = run_cmd(adb_cmd, debug)

    time.sleep(10)
    # Collect result
    wait_for_exit(serial)
    adb_cmd = f'adb {serial} shell ls /storage/emulated/0/Android/data/com.facebook.audiolat/files/*.raw'
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

    chirp = f'{REF_DIR}/chirp2_{short_rate}k_300ms.wav'
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
    chirp = f'{REF_DIR}/chirp2_{short_rate}k_300ms.wav'
    threshold = 80
    for file in local_files:
        if not os.path.exists(file):
            print('File does not exits - fix it :) ')
            exit(0)

        matches = ft.find_transients_raw(file, samplerate, chirp, file)
        matches.to_csv(f'{workdir}/{label}.csv', index=False)
        print(f'{matches}')
    return matches


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
            ret, stdout, stderr = run_cmd(adb_cmd, debug)
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
    print(f'Exit from {pid}')


def main():
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('-o', '--output', default='latency')
    parser.add_argument('-l', '--label', default='capture')
    parser.add_argument('-d', '--device', default=0)
    parser.add_argument('-s', '--serial', default='')
    options = parser.parse_args()

    global serial
    if len(options.serial) > 0:
        serial = f'-s {options.serial}'

    settings = {'content_type': None,
                'usage': None,
                'unput_preset': None}

    rates = [48000, 16000, 8000]
    apis = ['aaudio', 'javaaudio']#, 'oboe']
    apis = ['javaaudio']  # , 'oboe']
    for api in apis:
        for rate in rates:
            measure(rate, api, False, options.device, options.output,
                    f'{api}_{rate}_{options.label}', settings)


if __name__ == '__main__':
    main()
