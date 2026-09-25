"""USB-reachable thirdeye mock: adb reverse tcp:8765 tcp:8765."""

import argparse
import base64
import binascii
import json
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def jpeg_dimensions(image):
    if not image.startswith(b"\xff\xd8") or not image.endswith(b"\xff\xd9"):
        raise ValueError("invalid JPEG markers")
    pos = 2
    while pos + 4 <= len(image):
        if image[pos] != 0xFF:
            raise ValueError("invalid JPEG segment")
        while image[pos] == 0xFF:
            pos += 1
        marker = image[pos]
        pos += 1
        if marker == 0xDA:
            break
        if marker in (0x01, 0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
            continue
        length = int.from_bytes(image[pos : pos + 2], "big")
        if length < 2 or pos + length > len(image):
            raise ValueError("invalid JPEG segment length")
        if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            height = int.from_bytes(image[pos + 3 : pos + 5], "big")
            width = int.from_bytes(image[pos + 5 : pos + 7], "big")
            if width == 0 or height == 0:
                raise ValueError("empty JPEG dimensions")
            return width, height
        pos += length
    raise ValueError("JPEG dimensions missing")


class Handler(BaseHTTPRequestHandler):
    output_dir = Path(__file__).resolve().parents[1] / "captures"

    def do_POST(self):
        if self.path != "/analyze":
            self.send_error(404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 1 or length > 16_000_000:
                raise ValueError("invalid request size")
            request = json.loads(self.rfile.read(length))
            prompt = request["prompt"]
            if not isinstance(prompt, str):
                raise ValueError("prompt must be text")
            image = base64.b64decode(request["image_base64"], validate=True)
            width, height = jpeg_dimensions(image)
        except (ValueError, KeyError, TypeError, json.JSONDecodeError, binascii.Error) as exc:
            self.send_error(400, str(exc))
            return
        self.output_dir.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        path = self.output_dir / "latest.jpg"
        path.write_bytes(image)
        print(f"{stamp} bytes={len(image)} dimensions={width}x{height} prompt={prompt!r} saved={path}", flush=True)
        body = json.dumps({"answer": "MOCK: image received", "model_ms": 12.0}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--output-dir", type=Path, default=Handler.output_dir)
    args = parser.parse_args()
    Handler.output_dir = args.output_dir
    print(f"Listening on 127.0.0.1:{args.port}; JPEGs in {args.output_dir}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
