#!/usr/bin/env python3
"""
Checks which servers in a servers.json respond to a Minecraft status ping and writes a cleaned copy.

Usage:
    python3 check_servers.py servers.json                 -> writes servers.checked.json
    python3 check_servers.py servers.json -o out.json --timeout 4 --workers 32

Only the standard library is needed. If the optional 'dnspython' package is installed
(pip install dnspython), SRV records are used too, like the real game does. Without it, servers that
only work through an SRV record may be reported offline, so check the report before deleting anything.
"""
import argparse
import json
import socket
import struct
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

try:
    import dns.resolver  # type: ignore
except Exception:  # dnspython is optional
    dns = None


def write_varint(value: int) -> bytes:
    value &= 0xFFFFFFFF
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def read_varint(sock) -> int:
    value = 0
    for pos in range(0, 35, 7):
        chunk = sock.recv(1)
        if not chunk:
            raise EOFError("connection closed")
        b = chunk[0]
        value |= (b & 0x7F) << pos
        if not b & 0x80:
            return value
    raise ValueError("VarInt too big")


def read_exact(sock, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError("connection closed")
        buf += chunk
    return buf


def split_address(ip: str):
    host, port = ip.strip(), None
    if host.startswith("["):
        end = host.index("]")
        rest = host[end + 1:]
        host = host[1:end]
        if rest.startswith(":"):
            port = int(rest[1:])
    elif host.count(":") == 1:
        host, p = host.split(":")
        port = int(p)
    return host, port


def resolve(ip: str):
    host, port = split_address(ip)
    if port is None:
        if dns is not None:
            try:
                answer = dns.resolver.resolve(f"_minecraft._tcp.{host}", "SRV", lifetime=2)
                rec = sorted(answer, key=lambda r: r.priority)[0]
                return str(rec.target).rstrip("."), int(rec.port)
            except Exception:
                pass
        port = 25565
    return host, port


def flatten(desc) -> str:
    if desc is None:
        return ""
    if isinstance(desc, str):
        return desc
    if isinstance(desc, list):
        return "".join(flatten(x) for x in desc)
    if isinstance(desc, dict):
        return flatten(desc.get("text", "")) + flatten(desc.get("extra"))
    return str(desc)


def ping(ip: str, timeout: float):
    host, port = resolve(ip)
    with socket.create_connection((host, port), timeout=timeout) as s:
        s.settimeout(timeout)
        hostb = host.encode()
        handshake = write_varint(0) + write_varint(-1) + write_varint(len(hostb)) + hostb + struct.pack(">H", port) + write_varint(1)
        s.sendall(write_varint(len(handshake)) + handshake)
        s.sendall(write_varint(1) + write_varint(0))
        read_varint(s)  # packet length
        if read_varint(s) != 0:
            raise ValueError("unexpected packet")
        length = read_varint(s)
        if not 0 < length <= 2 * 1024 * 1024:
            raise ValueError("bad length")
        data = json.loads(read_exact(s, length).decode("utf-8", "replace"))
    players = data.get("players") or {}
    return {
        "online": int(players.get("online", 0) or 0),
        "max": int(players.get("max", 0) or 0),
        "version": (data.get("version") or {}).get("name", ""),
        "motd": flatten(data.get("description")).replace("\n", " ")[:80],
    }


def main():
    ap = argparse.ArgumentParser(description="Remove servers that do not answer a Minecraft status ping.")
    ap.add_argument("input")
    ap.add_argument("-o", "--output", default=None)
    ap.add_argument("--timeout", type=float, default=4.0)
    ap.add_argument("--workers", type=int, default=24)
    args = ap.parse_args()

    out_path = args.output or args.input.rsplit(".json", 1)[0] + ".checked.json"
    with open(args.input, encoding="utf-8") as f:
        doc = json.load(f)
    servers = doc["servers"] if isinstance(doc, dict) else doc

    results = {}
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(ping, s["ip"], args.timeout): s for s in servers}
        for i, fut in enumerate(as_completed(futures), 1):
            s = futures[fut]
            try:
                results[s["ip"]] = fut.result()
                r = results[s["ip"]]
                print(f"[{i}/{len(servers)}] ONLINE  {s['name']:<28} {r['online']}/{r['max']}", flush=True)
            except Exception as e:
                results[s["ip"]] = None
                print(f"[{i}/{len(servers)}] offline {s['name']:<28} ({type(e).__name__})", flush=True)

    kept = [s for s in servers if results.get(s["ip"])]
    dropped = [s for s in servers if not results.get(s["ip"])]
    if isinstance(doc, dict):
        doc["servers"] = kept
        doc["updated"] = __import__("datetime").date.today().isoformat()
    else:
        doc = kept
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)

    print(f"\nKept {len(kept)} online, dropped {len(dropped)} offline -> {out_path}")
    if dropped:
        print("Dropped:", ", ".join(s["name"] for s in dropped))
    if dns is None:
        print("Note: dnspython is not installed, so SRV records were not used. "
              "Install it (pip install dnspython) if many known-good servers show as offline.")


if __name__ == "__main__":
    sys.exit(main())
