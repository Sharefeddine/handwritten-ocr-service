"""Send a local image to the running OCR service using only the standard library."""

import argparse
import base64
import json
import urllib.error
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Try the handwritten OCR API")
    parser.add_argument("image", type=Path, help="Path to an image to recognize")
    parser.add_argument(
        "--url",
        default="http://127.0.0.1:8000/recognize",
        help="Recognition endpoint (default: %(default)s)",
    )
    args = parser.parse_args()

    payload = json.dumps({"image": base64.b64encode(args.image.read_bytes()).decode("ascii")})
    request = urllib.request.Request(
        args.url,
        data=payload.encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            result = json.load(response)
    except urllib.error.HTTPError as exc:
        parser.exit(1, f"HTTP {exc.code}: {exc.read().decode('utf-8', errors='replace')}\n")
    except urllib.error.URLError as exc:
        parser.exit(1, f"Could not connect to OCR service: {exc.reason}\n")

    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
