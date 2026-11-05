#!/usr/bin/env python3
"""Exercise RTSP reconnect and process-death recovery against the loopback synthetic server."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sdk = os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
    parser.add_argument('--adb', default=str(Path(sdk) / 'platform-tools/adb'))
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'emulator-5554'))
    parser.add_argument('--output', type=Path, default=Path('tools/vendor') / time.strftime('resilience-%Y%m%d-%H%M%S'))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, '-s', args.serial]
    base = '/sdcard/Android/data/com.rtspmonitor.app/files/control/'
    runner = 'com.rtspmonitor.app.test/androidx.test.runner.AndroidJUnitRunner'
    processes = []

    def command(*parts):
        return subprocess.check_output(adb + list(parts), text=True, stderr=subprocess.STDOUT)

    def start(method, name, extras=()):
        output = (args.output / name).open('w')
        invocation = adb + ['shell', 'am', 'instrument', '-w', '-e', 'class',
                           'com.rtspmonitor.app.RecoveryLifecycleTest#' + method,
                           '-e', 'rtspBase', 'rtsp://10.0.2.2:18554', *extras, runner]
        process = subprocess.Popen(invocation, stdout=output, stderr=subprocess.STDOUT)
        processes.append(process)
        return process, output

    def marker(name, process):
        deadline = time.monotonic() + 50
        while time.monotonic() < deadline:
            result = subprocess.run(adb + ['shell', 'cat', base + name], capture_output=True, text=True)
            if result.returncode == 0 and result.stdout.strip():
                return result.stdout.strip()
            if process.poll() is not None:
                raise RuntimeError('Instrumentation ended before its ready marker; inspect logs')
            time.sleep(0.2)
        raise TimeoutError('Ready marker timed out')

    def passed(process, output, name):
        process.wait(timeout=60)
        output.close()
        text = (args.output / name).read_text()
        if '\nOK (' not in text:
            raise RuntimeError(f'Instrumentation failed: {args.output / name}')

    try:
        command('shell', 'rm', '-f', base + 'reconnect-ready', base + 'crash-ready')
        process, output = start('interruptedSessionReconnectsIntoNewFile', 'reconnect.txt')
        marker('reconnect-ready', process)
        with urllib.request.urlopen('http://127.0.0.1:19997/v3/rtspsessions/list', timeout=5) as response:
            sessions = json.load(response)['items']
        readers = [s for s in sessions if s['state'] == 'read' and s['path'] == 'h264' and s.get('conns')]
        if len(readers) != 1:
            raise RuntimeError('Expected one synthetic h264 reader; refusing to terminate ambiguous sessions')
        session_id = readers[0]['id']
        request = urllib.request.Request('http://127.0.0.1:19997/v3/rtspsessions/kick/' + session_id, data=b'', method='POST')
        with urllib.request.urlopen(request, timeout=5) as response:
            assert response.status == 200
        passed(process, output, 'reconnect.txt')
        print('Reconnect and new-file publication passed', flush=True)

        process, output = start('prepareProcessDeath', 'expected-process-death.txt')
        camera_id = marker('crash-ready', process)
        command('shell', 'am', 'force-stop', 'com.rtspmonitor.app')
        process.wait(timeout=15)
        output.close()
        process, output = start('recoverAfterProcessDeath', 'process-recovery.txt', ['-e', 'recoveryCameraId', camera_id])
        passed(process, output, 'process-recovery.txt')
        (args.output / 'recovered.json').write_text(command('shell', 'cat', base + 'recovered.json'))
        print('Process-death recovery passed; preparatory instrumentation termination was intentional', flush=True)
    finally:
        interrupted = any(process.poll() is None for process in processes)
        for process in processes:
            if process.poll() is None:
                process.terminate()
        if interrupted:
            command('shell', 'am', 'force-stop', 'com.rtspmonitor.app')
        command('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    print('Artifacts:', args.output)


if __name__ == '__main__':
    main()
