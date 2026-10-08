# Handwritten OCR service

A small, general-purpose FastAPI service for recognizing handwritten text in notes, documents, and other images using PaddleOCR on **CPU**. Results include the combined text, individual lines with confidence scores, and inference time in milliseconds. Recognition is configured for English.

## Install

Use a Python version supported by the pinned PaddlePaddle 2.x packages (for example Python 3.10 or 3.11). A network connection is needed on the first recognition request to download PaddleOCR model weights.

```bash
python -m venv .venv
source .venv/bin/activate  # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

## Run

```bash
uvicorn main:app --reload --no-access-log
```

Check `http://127.0.0.1:8000/health` for `{"status":"ok"}`.

Application logs are one JSON object per line on stdout. Each request logs `request_completed` with UTC timestamp, HTTP method, path, status code, and total duration. Successful OCR also logs `recognition_completed` with inference duration and line count. Image data and recognized text are **not** logged. `--no-access-log` avoids duplicate plain-text Uvicorn access entries; Uvicorn startup/error messages may still use its default format.

```json
{"timestamp":"2026-10-08T12:00:00+00:00","level":"INFO","event":"request_completed","method":"POST","path":"/recognize","status_code":200,"duration_ms":153.4}
```

## Recognize an image

With the included test client (start the server first):

```bash
python test_client.py note.png
# To target a different server: python test_client.py note.png --url http://localhost:9000/recognize
```

The client sends base64 JSON and prints the full API response. It uses only the Python standard library.

Upload a file with curl:

```bash
curl -X POST http://127.0.0.1:8000/recognize -F 'image=@note.png'
```

Or send a base64-encoded image in JSON (a base64 data URI also works):

```bash
curl -X POST http://127.0.0.1:8000/recognize \
  -H 'Content-Type: application/json' \
  -d "{\"image\":\"$(base64 < note.png | tr -d '\n')\"}"
```

Example response:

```json
{"text":"Hello world","lines":[{"text":"Hello world","confidence":0.95}],"took_ms":142.3}
```

The service explicitly disables GPU inference. Model loading and any initial download happen on the first recognition request; `/health` does not load the model.
