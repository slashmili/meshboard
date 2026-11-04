#!/usr/bin/env python3
"""Check a bounded Ethernet PCAP of local test ports, without printing payloads.
This complements crypto/admission tests; absence of strings alone is not a proof.
"""
import json
import struct
import sys
from pathlib import Path

path = Path(sys.argv[1])
assert path.stat().st_size <= 32 * 1024 * 1024, 'Capture exceeds 32 MiB'
data = path.read_bytes()
assert data[:4] in (b'\xd4\xc3\xb2\xa1', b'\xa1\xb2\xc3\xd4'), 'Expected PCAP'
order = '<' if data[:4] == b'\xd4\xc3\xb2\xa1' else '>'
assert struct.unpack_from(order + 'I', data, 20)[0] == 1, 'Expected Ethernet capture'
streams = {}
packets = turn_records = encrypted_turn = signaling_messages = 0
cursor = 24
while cursor < len(data):
    assert cursor + 16 <= len(data)
    size = struct.unpack_from(order + 'I', data, cursor + 8)[0]
    cursor += 16
    assert cursor + size <= len(data)
    packet = data[cursor:cursor + size]; cursor += size; packets += 1
    if len(packet) < 34 or packet[12:14] != b'\x08\x00':
        continue
    ip = packet[14:]
    header_size = (ip[0] & 15) * 4
    transport = ip[header_size:struct.unpack_from('!H', ip, 2)[0]]
    if ip[9] == 6 and len(transport) >= 20:
        source, destination, sequence = struct.unpack_from('!HHI', transport)
        if 5173 not in (source, destination):
            continue
        payload = transport[(transport[12] >> 4) * 4:]
        if payload:
            stream = streams.setdefault((ip[12:16], ip[16:20], source, destination), {})
            stream[sequence] = payload
    elif ip[9] == 17 and len(transport) >= 8:
        source, destination, length = struct.unpack_from('!HHH', transport)
        if 3478 not in (source, destination):
            continue
        payload = transport[8:length]
        records = []
        if len(payload) >= 4 and payload[0] & 0xc0 == 0x40:
            records.append(payload[4:4 + struct.unpack_from('!H', payload, 2)[0]])
        elif len(payload) >= 20 and payload[4:8] == b'\x21\x12\xa4\x42':
            offset = 20
            while offset + 4 <= len(payload):
                kind, size = struct.unpack_from('!HH', payload, offset); offset += 4
                assert offset + size <= len(payload)
                if kind == 0x13:
                    records.append(payload[offset:offset + size])
                offset += (size + 3) & ~3
        for record in records:
            if len(record) >= 20 and record[4:8] == b'\x21\x12\xa4\x42':
                # ICE connectivity checks are metadata, not application data.
                assert struct.unpack_from('!H', record)[0] in (0x0001, 0x0101, 0x0111)
                continue
            classic = len(record) >= 13 and record[0] in (20, 21, 22, 23) and record[1:3] in (b'\xfe\xfd', b'\xfe\xff')
            # RFC 9147 section 4: DTLS 1.3 ciphertext uses a unified header (001...).
            unified = len(record) >= 18 and record[0] & 0xe0 == 0x20
            assert classic or unified, 'Unexpected unencrypted relay payload'
            turn_records += 1
            if record[0] == 23 or (unified and record[0] & 3 == 3):
                encrypted_turn += 1
            for marker in (b'"points"', b'"color"', b'"preview"', b'"elements"'):
                assert marker not in record, 'Board marker in relay payload'

for segments in streams.values():
    stream = bytearray(); end = None
    for sequence, payload in sorted(segments.items()):
        if end is not None:
            assert sequence <= end, 'Incomplete TCP capture'
            payload = payload[max(0, end - sequence):]
        stream.extend(payload); end = sequence + len(segments[sequence]) if end is None else max(end, sequence + len(segments[sequence]))
    boundary = stream.find(b'\r\n\r\n')
    if boundary < 0 or b'upgrade: websocket' not in stream[:boundary].lower():
        continue
    offset = boundary + 4
    message = bytearray()
    while offset + 2 <= len(stream):
        first, second = stream[offset:offset + 2]; offset += 2
        size = second & 127
        if size == 126:
            size = struct.unpack_from('!H', stream, offset)[0]; offset += 2
        elif size == 127:
            size = struct.unpack_from('!Q', stream, offset)[0]; offset += 8
        assert size <= 65536
        mask = stream[offset:offset + 4] if second & 128 else None
        if mask is not None:
            offset += 4
        assert offset + size <= len(stream), 'Truncated WebSocket record'
        payload = bytes(stream[offset + i] ^ (mask[i % 4] if mask else 0) for i in range(size)); offset += size
        if first & 15 not in (0, 1):
            continue
        assert not first & 0x70, 'Compressed signaling requires a decoder'
        message.extend(payload)
        if first & 128:
            value = json.loads(message)
            assert value['type'] in ('subscribe', 'unsubscribe', 'publish', 'ping', 'pong', 'error')
            for marker in ('"key"', '"points"', '"color"', '"preview"', '"elements"'):
                assert marker.encode() not in message, 'Secret/content field in signaling'
            signaling_messages += 1; message.clear()
assert signaling_messages > 0, 'No decoded signaling messages'
assert encrypted_turn > 0, 'No DTLS application records on TURN'
print(json.dumps({'packets': packets, 'signaling_messages': signaling_messages, 'turn_dtls_records': turn_records, 'turn_encrypted_application_records': encrypted_turn, 'result': 'passed'}))
