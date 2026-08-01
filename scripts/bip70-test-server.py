#!/usr/bin/env python3
"""Minimal BIP70/BIP270 test server for the Dash testnet wallet (stdlib only).

Serves a one-output PaymentRequest and acks the returned Payment — enough to
exercise the wallet's full scanned-invoice flow (PaymentProtocolFragment:
fetch -> preview -> confirm -> Payment POST -> ACK) against a device or
emulator, with no external service and no real network.

Endpoints:
  GET  /invoice  -> application/dash-paymentrequest (PaymentRequest protobuf)
  POST /pay      -> application/dash-paymentack     (echoes the Payment in an ACK)

Usage (device connected via adb):
  python3 scripts/bip70-test-server.py [dest_address] [amount_duffs] [port]
  adb reverse tcp:8330 tcp:8330
  adb shell am start -a android.intent.action.VIEW \
      -d "dash:?r=http://127.0.0.1:8330/invoice" hashengineering.darkcoin.wallet_test

Defaults: pay 1_000_000 duffs (0.01 tDASH) to the Dash testnet faucet
(https://faucet.testnet.networks.dash.org/ — same hot wallet as
faucet.thepasta.org, the FAUCET_URL in de.schildbach.wallet.Constants), so
test payments recycle back into the faucet pool. Pass your own wallet's
receive address instead for a self-pay that only costs the fee.

Post-cutover (state CUT_OVER) the wallet routes this through the SDK deferred
build/broadcast surface: expect `l1DeferredBuild` in logcat when the preview
opens (exact fee, inputs reserved), and `l1DeferredBroadcast` of the SAME
txid after confirm+ACK. Pre-cutover it exercises the legacy dashj path.
"""
import sys
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

# The Dash testnet faucet's hot wallet (faucet.testnet.networks.dash.org /
# faucet.thepasta.org — verified on-chain: 1 tDASH fan-out payouts).
FAUCET_ADDRESS = "yjSvwyLB5X4dqQqVMPMu6UdrFpYZ3u9v5U"

B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"


def b58decode_check(s: str) -> bytes:
    n = 0
    for ch in s:
        n = n * 58 + B58.index(ch)
    raw = n.to_bytes(25, "big")
    return raw[:-4]  # version byte + hash160 (checksum not re-verified here)


def p2pkh_script(address: str) -> bytes:
    payload = b58decode_check(address)
    h160 = payload[1:21]
    return b"\x76\xa9\x14" + h160 + b"\x88\xac"


def varint(n: int) -> bytes:
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        out += bytes([b | (0x80 if n else 0)])
        if not n:
            return out


def field(num: int, wire: int, payload: bytes) -> bytes:
    return varint((num << 3) | wire) + payload


def ld(num: int, data: bytes) -> bytes:  # length-delimited
    return field(num, 2, varint(len(data)) + data)


def uv(num: int, val: int) -> bytes:  # varint field
    return field(num, 0, varint(val))


def build_payment_request(address: str, duffs: int, payment_url: str, memo: str) -> bytes:
    output = uv(1, duffs) + ld(2, p2pkh_script(address))
    now = int(time.time())
    details = (
        ld(1, b"test")                    # network
        + ld(2, output)                   # outputs
        + uv(3, now)                      # time
        + uv(4, now + 3600)               # expires
        + ld(5, memo.encode())            # memo
        + ld(6, payment_url.encode())     # payment_url
        + ld(7, b"bip70-test-server")     # merchant_data
    )
    return (
        uv(1, 1)                          # payment_details_version
        + ld(2, b"none")                  # pki_type
        + ld(4, details)                  # serialized_payment_details
    )


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("[bip70]", fmt % args, flush=True)

    def do_GET(self):
        if self.path != "/invoice":
            self.send_error(404)
            return
        body = build_payment_request(DEST, DUFFS, PAY_URL, "BIP70 test invoice")
        self.send_response(200)
        self.send_header("Content-Type", "application/dash-paymentrequest")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        print(f"[bip70] served PaymentRequest: {DUFFS} duffs -> {DEST}", flush=True)

    def do_POST(self):
        if self.path != "/pay":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        payment = self.rfile.read(length)
        print(f"[bip70] received Payment ({len(payment)} bytes)", flush=True)
        # PaymentACK: field 1 = the Payment message verbatim, field 2 = memo.
        # Reply memo "nack" instead to exercise the wallet's release path.
        ack = ld(1, payment) + ld(2, b"ack")
        self.send_response(200)
        self.send_header("Content-Type", "application/dash-paymentack")
        self.send_header("Content-Length", str(len(ack)))
        self.end_headers()
        self.wfile.write(ack)
        print("[bip70] sent PaymentACK", flush=True)


if __name__ == "__main__":
    DEST = sys.argv[1] if len(sys.argv) > 1 else FAUCET_ADDRESS
    DUFFS = int(sys.argv[2]) if len(sys.argv) > 2 else 1_000_000
    port = int(sys.argv[3]) if len(sys.argv) > 3 else 8330
    PAY_URL = f"http://127.0.0.1:{port}/pay"
    print(f"[bip70] serving invoice for {DUFFS} duffs to {DEST} on :{port}", flush=True)
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
