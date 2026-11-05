#!/usr/bin/env python3
"""Compare VCL NAL payloads of a looping synthetic MP4 source and a recorded segment."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def unhex_dump(dump):
    return bytes.fromhex(''.join(line.split(':', 1)[1].split('  ', 1)[0].strip().replace(' ', '')
                                for line in dump.splitlines() if ':' in line))


def vcl_hashes(document):
    stream = document['streams'][0]
    codec = stream['codec_name']
    assert codec in ('h264', 'hevc')
    config = unhex_dump(stream['extradata'])
    assert config[0] == 1, 'Expected MP4 codec configuration'
    length_size = (config[4 if codec == 'h264' else 21] & 3) + 1
    result = []
    for packet in document['packets']:
        data = unhex_dump(packet['data'])
        offset = 0
        while offset < len(data):
            assert offset + length_size <= len(data), 'Truncated NAL length'
            size = int.from_bytes(data[offset:offset + length_size], 'big')
            offset += length_size
            assert size > 0 and offset + size <= len(data), 'Truncated NAL data'
            nal = data[offset:offset + size]
            offset += size
            kind = nal[0] & 31 if codec == 'h264' else (nal[0] >> 1) & 63
            if (codec == 'h264' and 1 <= kind <= 5) or (codec == 'hevc' and kind <= 31):
                result.append(hashlib.sha256(nal).hexdigest())
    assert result, 'No video slice payloads'
    return result


def matching_offset(source, segment):
    assert source and segment
    for offset in range(len(source)):
        if all(value == source[(offset + i) % len(source)] for i, value in enumerate(segment)):
            return offset
    raise AssertionError('Video slice payload differs from the looping fixture')


def aac_hashes(document):
    assert document['streams'][0]['codec_name'] == 'aac', 'Expected AAC'
    result = [hashlib.sha256(unhex_dump(packet['data'])).hexdigest() for packet in document['packets']]
    assert result, 'No AAC access units'
    return result


def probe(path, stream='v:0'):
    assert path.is_file(), 'A local MP4 file is required'
    output = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', stream, '-show_streams',
                             '-show_packets', '-show_data', '-of', 'json', str(path.resolve())],
                            check=True, capture_output=True, text=True)
    return json.loads(output.stdout)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('segment', type=Path)
    parser.add_argument('--audio', action='store_true', help='Compare raw AAC access units instead of video VCL NALs')
    args = parser.parse_args()
    extract = aac_hashes if args.audio else vcl_hashes
    stream = 'a:0' if args.audio else 'v:0'
    expected = extract(probe(args.source, stream))
    actual = extract(probe(args.segment, stream))
    print(json.dumps({'matched_source_offset': matching_offset(expected, actual), 'matching_aac_packets' if args.audio else 'matching_vcl_nals': len(actual)}))
