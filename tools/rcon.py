"""A minimal RCON client, enough to drive a Minecraft server from a script.

Written because there is no other way to exercise the command tree headlessly: GameTests never
register a command, and the dev client cannot be typed into from here.

    python tools/rcon.py "tplus create Hunter 3" "tplus list" "tplus removeall"
    RCON_PORT=25576 python tools/rcon.py "stop"

Host, port and password come from the environment (RCON_HOST, RCON_PORT, RCON_PASSWORD) and
default to the dev server in run/server.properties. Each command is sent in order with a short
pause, and the server's reply is printed -- which is the command feedback a player would see in
chat, so this doubles as a way to read /tplus info.
"""
import os
import socket
import struct
import sys
import time

HOST = os.environ.get('RCON_HOST', '127.0.0.1')
PORT = int(os.environ.get('RCON_PORT', '25575'))
PASSWORD = os.environ.get('RCON_PASSWORD', 'tplus')


def pack(req_id, req_type, body):
    payload = struct.pack('<ii', req_id, req_type) + body.encode('utf-8') + b'\x00\x00'
    return struct.pack('<i', len(payload)) + payload


def read(sock):
    raw_len = b''
    while len(raw_len) < 4:
        chunk = sock.recv(4 - len(raw_len))
        if not chunk:
            raise EOFError('connection closed')
        raw_len += chunk
    size = struct.unpack('<i', raw_len)[0]
    data = b''
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise EOFError('connection closed')
        data += chunk
    req_id, req_type = struct.unpack('<ii', data[:8])
    return req_id, req_type, data[8:-2].decode('utf-8', 'replace')


def main(commands):
    sock = socket.create_connection((HOST, PORT), timeout=30)
    sock.settimeout(30)

    sock.sendall(pack(1, 3, PASSWORD))
    req_id, _, _ = read(sock)

    if req_id == -1:
        print('AUTH FAILED')
        return 1

    for i, cmd in enumerate(commands):
        sock.sendall(pack(10 + i, 2, cmd))
        _, _, body = read(sock)
        print('> ' + cmd)
        print(body.strip() or '(no output)')
        time.sleep(1.5)

    sock.close()
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
