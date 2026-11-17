#!/usr/bin/env python3
"""
Seals the Ahamove staging credentials for the seeded Ahamove carrier and writes them as psql \\set lines, so the seed
can make that carrier API_BOOKING without the key ever being written into a file of the repository, typed into a
shell's history, or put on a command line where `ps` shows it (review S-3, S-4):

    read -rs STOCKFLOW_SECRETS_KEY        # the deployment's own secrets key (base64 of 32 bytes)
    read -rs AHAMOVE_STAGING_API_KEY      # the partner API key Ahamove issued for staging
    read -rs AHAMOVE_WEBHOOK_TOKEN        # the value Ahamove is configured to send in X-Ahamove-Token
    read -r  AHAMOVE_API_ACCOUNT          # the partner account (the mobile number the key belongs to)
    { STOCKFLOW_SECRETS_KEY=$STOCKFLOW_SECRETS_KEY AHAMOVE_STAGING_API_KEY=$AHAMOVE_STAGING_API_KEY \\
      AHAMOVE_WEBHOOK_TOKEN=$AHAMOVE_WEBHOOK_TOKEN AHAMOVE_API_ACCOUNT=$AHAMOVE_API_ACCOUNT \\
      python tools/db/seed/seal_ahamove_key.py; cat tools/db/seed/seed_test_data.sql; } \\
      | psql -v ON_ERROR_STOP=1 -h <host> -U stockflow -d stockflow

The variables are given to this one process only, and the sealed values reach psql on its input. Nothing secret is
printed: the webhook token is never generated here, so it is never shown - it is the value configured at Ahamove.

Without these variables the seed still loads, and Ahamove is a MANUAL carrier until its key is entered on the carrier
screen (the database refuses API_BOOKING without a sealed key, ck_carrier_connection_api_booking).

The sealed form is exactly SecretCipher's: "v1:" + base64(nonce || ciphertext || tag), AES-256-GCM with a random 96-bit
nonce and the context "carrier:<carrier id>:<setting>" as associated data - so the application opens it with the same
STOCKFLOW_SECRETS_KEY, and only on that carrier's row. Pure Python (no third-party package): AES and GHASH are written
out below and checked against the GCM specification's test case 16 by --self-test.
"""

import base64
import hashlib
import os
import secrets
import sys

# The seeded carrier's id: md5('seed:carrier:AHAMOVE')::uuid, as gen_seed_test_data.py writes it.
_h = hashlib.md5(b"seed:carrier:AHAMOVE").hexdigest()
CARRIER_ID = f"{_h[:8]}-{_h[8:12]}-{_h[12:16]}-{_h[16:20]}-{_h[20:]}"

# ---------------------------------------------------------------------------------------------------------------- AES
SBOX = [0] * 256


def _build_sbox():
    p = q = 1
    while True:
        p = p ^ ((p << 1) & 0xFF) ^ (0x1B if p & 0x80 else 0)
        q ^= q << 1
        q ^= q << 2
        q ^= q << 4
        q &= 0xFF
        if q & 0x80:
            q ^= 0x09
        x = q ^ ((q << 1) | (q >> 7)) ^ ((q << 2) | (q >> 6)) ^ ((q << 3) | (q >> 5)) ^ ((q << 4) | (q >> 4))
        SBOX[p] = (x ^ 0x63) & 0xFF
        if p == 1:
            break
    SBOX[0] = 0x63


_build_sbox()


def _xtime(a):
    return ((a << 1) ^ 0x1B) & 0xFF if a & 0x80 else a << 1


def _expand_key(key):
    nk, nr = len(key) // 4, {16: 10, 24: 12, 32: 14}[len(key)]
    words = [list(key[4 * i:4 * i + 4]) for i in range(nk)]
    rcon = 1
    for i in range(nk, 4 * (nr + 1)):
        t = list(words[i - 1])
        if i % nk == 0:
            t = [SBOX[b] for b in t[1:] + t[:1]]
            t[0] ^= rcon
            rcon = _xtime(rcon)
        elif nk > 6 and i % nk == 4:
            t = [SBOX[b] for b in t]
        words.append([a ^ b for a, b in zip(words[i - nk], t)])
    return [sum(words[4 * r:4 * r + 4], []) for r in range(nr + 1)]


def _encrypt_block(round_keys, block):
    s = [b ^ k for b, k in zip(block, round_keys[0])]
    for r in range(1, len(round_keys)):
        s = [SBOX[b] for b in s]
        s = [s[(i + 4 * (i % 4)) % 16] for i in range(16)]          # ShiftRows (column-major state)
        if r < len(round_keys) - 1:
            m = []
            for c in range(4):
                a = s[4 * c:4 * c + 4]
                t = a[0] ^ a[1] ^ a[2] ^ a[3]
                m += [a[i] ^ t ^ _xtime(a[i] ^ a[(i + 1) % 4]) for i in range(4)]
            s = m
        s = [b ^ k for b, k in zip(s, round_keys[r])]
    return bytes(s)


# ---------------------------------------------------------------------------------------------------------------- GCM
def _gmul(x, y):
    r = 0xE1 << 120
    z, v = 0, y
    for i in range(127, -1, -1):
        if (x >> i) & 1:
            z ^= v
        v = (v >> 1) ^ r if v & 1 else v >> 1
    return z


def _ghash(h, data):
    y = 0
    for i in range(0, len(data), 16):
        y = _gmul(y ^ int.from_bytes(data[i:i + 16], "big"), h)
    return y


def _pad(b):
    return b + bytes(-len(b) % 16)


def gcm_encrypt(key, nonce, plaintext, aad):
    rk = _expand_key(key)
    h = int.from_bytes(_encrypt_block(rk, bytes(16)), "big")
    j0 = nonce + b"\x00\x00\x00\x01"
    counter = int.from_bytes(j0, "big")
    out = bytearray()
    for i in range(0, len(plaintext), 16):
        counter = (counter & ~0xFFFFFFFF) | ((counter + 1) & 0xFFFFFFFF)
        stream = _encrypt_block(rk, counter.to_bytes(16, "big"))
        out += bytes(a ^ b for a, b in zip(plaintext[i:i + 16], stream))
    lengths = (len(aad) * 8).to_bytes(8, "big") + (len(out) * 8).to_bytes(8, "big")
    s = _ghash(h, _pad(aad) + _pad(bytes(out)) + lengths)
    tag = int.from_bytes(_encrypt_block(rk, j0), "big") ^ s
    return bytes(out), tag.to_bytes(16, "big")


def seal(key, secret, context):
    nonce = secrets.token_bytes(12)
    sealed, tag = gcm_encrypt(key, nonce, secret.encode("utf-8"), context.encode("utf-8"))
    return "v1:" + base64.b64encode(nonce + sealed + tag).decode("ascii")


def self_test():
    """GCM specification, test case 16 (AES-256, 96-bit IV, with associated data)."""
    k = bytes.fromhex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
    iv = bytes.fromhex("cafebabefacedbaddecaf888")
    p = bytes.fromhex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525"
                      "b16aedf5aa0de657ba637b39")
    a = bytes.fromhex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
    c, t = gcm_encrypt(k, iv, p, a)
    assert c.hex() == ("522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838"
                       "c5f61e6393ba7a0abcc9f662"), c.hex()
    assert t.hex() == "76fc6ece0f4e1768cddf8853bb2d551b", t.hex()
    print("AES-256-GCM self-test passed", file=sys.stderr)


def main():
    if "--self-test" in sys.argv:
        self_test()
        return
    names = ("STOCKFLOW_SECRETS_KEY", "AHAMOVE_STAGING_API_KEY", "AHAMOVE_API_ACCOUNT", "AHAMOVE_WEBHOOK_TOKEN")
    env = {name: os.environ.get(name, "").strip() for name in names}
    missing = [n for n in names if not env[n]]
    if missing:
        sys.exit("seal_ahamove_key.py: set " + ", ".join(missing))
    key = base64.b64decode(env["STOCKFLOW_SECRETS_KEY"])
    if len(key) != 32:
        sys.exit("seal_ahamove_key.py: STOCKFLOW_SECRETS_KEY must be base64 of exactly 32 bytes")
    token = env["AHAMOVE_WEBHOOK_TOKEN"]
    account = env["AHAMOVE_API_ACCOUNT"]
    if not (3 <= len(account) <= 64) or any(not 33 <= ord(ch) <= 126 or ch in "'\\" for ch in account):
        sys.exit("seal_ahamove_key.py: AHAMOVE_API_ACCOUNT is 3 to 64 printable characters without spaces or quotes")
    api_key = seal(key, env["AHAMOVE_STAGING_API_KEY"], f"carrier:{CARRIER_ID}:api-key")
    auth = seal(key, token, f"carrier:{CARRIER_ID}:webhook-auth-value")
    # Base64 and the account's printable characters hold no quote, so each value is one quoted psql word.
    print(f"\\set ahamove_api_key_sealed '{api_key}'")
    print(f"\\set ahamove_webhook_auth_sealed '{auth}'")
    print(f"\\set ahamove_api_account '{account}'")


if __name__ == "__main__":
    main()
