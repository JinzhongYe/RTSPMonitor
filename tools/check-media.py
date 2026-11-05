#!/usr/bin/env python3
"""Inspect a local recording with ffprobe. This is not a decoder/hardware test."""
import argparse
import json
import subprocess
from pathlib import Path

def inspect(document):
    streams = document['streams']
    video = next(s for s in streams if s['codec_type'] == 'video')
    assert video['codec_name'] in ('h264', 'hevc'), 'Unsupported video codec'
    packets = [p for p in document['packets'] if p['stream_index'] == video['index']]
    assert packets and 'K' in packets[0].get('flags', ''), 'Missing first video keyframe'
    dts = [float(p['dts_time']) for p in packets if 'dts_time' in p]
    assert len(dts) == len(packets) and all(b > a for a, b in zip(dts, dts[1:])), 'Invalid DTS'
    assert float(document['format']['duration']) > 0, 'Invalid duration'
    return {'codec': video['codec_name'], 'width': video['width'], 'height': video['height'], 'duration': document['format']['duration'], 'video_packets': len(packets)}

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('file', type=Path)
    args = parser.parse_args()
    if not args.file.is_file():
        parser.error('A local media file is required')
    result = subprocess.run(['ffprobe', '-v', 'error', '-show_format', '-show_streams', '-show_packets', '-of', 'json', str(args.file.resolve())], check=True, capture_output=True, text=True)
    print(json.dumps(inspect(json.loads(result.stdout)), ensure_ascii=False, indent=2))
