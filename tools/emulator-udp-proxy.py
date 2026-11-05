#!/usr/bin/env python3
"""Loopback-only RTSP control proxy adding emulator UDP routes from SETUP client_port.
RTP/RTCP remain UDP. No media re-encoding or RTP forwarding occurs in this process.
"""
import os
import signal
from pathlib import Path
import re
import socket
import socketserver
import subprocess
import threading

sdk = os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
adb = [str(Path(sdk) / 'platform-tools/adb'), '-s', os.environ.get('ANDROID_SERIAL', 'emulator-5554'), 'emu', 'redir']
routes = set()
lock = threading.Lock()


def add_routes(request):
    if not request.startswith(b'SETUP '):
        return []
    match = re.search(rb'client_port=(\d+)-(\d+)', request)
    if not match:
        return []
    ports = list(map(int, match.groups()))
    for port in ports:
        assert 1024 <= port <= 65535
        with lock:
            assert port not in routes, 'Port is already owned by another test session'
            result = subprocess.run(adb + ['add', f'udp:{port}:{port}'], capture_output=True, text=True, check=True)
            assert 'OK' in result.stdout and 'KO' not in result.stdout, 'Cannot add UDP redirection'
            routes.add(port)
        print('UDP route added:', port, flush=True)
    return ports


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        owned = []
        done = threading.Event()
        upstream = socket.create_connection(('127.0.0.1', 18554), timeout=5)
        self.request.settimeout(1)
        upstream.settimeout(1)

        def responses():
            try:
                while not done.is_set():
                    try:
                        data = upstream.recv(65536)
                    except socket.timeout:
                        continue
                    if not data:
                        break
                    self.request.sendall(data)
            except OSError:
                pass
            finally:
                done.set()

        worker = threading.Thread(target=responses, daemon=True)
        worker.start()
        try:
            buffer = b''
            while not done.is_set():
                try:
                    data = self.request.recv(65536)
                except socket.timeout:
                    continue
                if not data:
                    break
                buffer += data
                assert len(buffer) < 128 * 1024, 'Oversized RTSP request'
                while b'\r\n\r\n' in buffer:
                    header_end = buffer.index(b'\r\n\r\n') + 4
                    match = re.search(rb'Content-Length:\s*(\d+)', buffer[:header_end], re.I)
                    length = header_end + (int(match.group(1)) if match else 0)
                    if len(buffer) < length:
                        break
                    request, buffer = buffer[:length], buffer[length:]
                    owned.extend(add_routes(request))
                    upstream.sendall(request)
        finally:
            done.set()
            upstream.close()
            worker.join(timeout=2)
            for port in owned:
                subprocess.run(adb + ['del', f'udp:{port}'], capture_output=True)
                with lock:
                    routes.discard(port)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == '__main__':
    def stop(*_):
        raise SystemExit(0)
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        with Server(('127.0.0.1', 18555), Handler) as server:
            print('RTSP control proxy ready on loopback:18555', flush=True)
            server.serve_forever()
    finally:
        for port in list(routes):
            subprocess.run(adb + ['del', f'udp:{port}'], capture_output=True)
