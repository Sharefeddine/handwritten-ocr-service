"""CPU-only handwritten text recognition API."""

import base64
import binascii
import io
import time
from functools import lru_cache

import numpy as np
from fastapi import FastAPI, HTTPException, Request
from PIL import Image, UnidentifiedImageError
from starlette.concurrency import run_in_threadpool
from starlette.datastructures import UploadFile

app = FastAPI(title="Handwritten OCR Service")


@lru_cache(maxsize=1)
def get_ocr():
    # Load lazily: the first request may download model weights and take longer.
    from paddleocr import PaddleOCR

    return PaddleOCR(lang="en", use_gpu=False, show_log=False)


@app.get("/health")
def health():
    return {"status": "ok"}


async def read_image(request: Request) -> bytes:
    content_type = request.headers.get("content-type", "").split(";", 1)[0].lower()
    if content_type == "multipart/form-data":
        form = await request.form()
        image = form.get("image")
        if not isinstance(image, UploadFile):
            raise HTTPException(400, "Provide a file in the 'image' form field")
        return await image.read()
    if content_type == "application/json":
        try:
            payload = await request.json()
        except ValueError as exc:
            raise HTTPException(400, "Invalid JSON") from exc
        if not isinstance(payload, dict) or not isinstance(payload.get("image"), str):
            raise HTTPException(400, "Provide a base64 string in the 'image' JSON field")
        encoded = payload["image"]
        if encoded.startswith("data:"):
            if ";base64," not in encoded:
                raise HTTPException(400, "Invalid base64 image")
            encoded = encoded.split(";base64,", 1)[1]
        try:
            return base64.b64decode(encoded, validate=True)
        except (ValueError, binascii.Error) as exc:
            raise HTTPException(400, "Invalid base64 image") from exc
    raise HTTPException(415, "Use multipart/form-data or application/json")


def recognize_image(data: bytes):
    try:
        with Image.open(io.BytesIO(data)) as image:
            image_array = np.array(image.convert("RGB"))
    except (UnidentifiedImageError, OSError, ValueError) as exc:
        raise HTTPException(400, "Invalid image") from exc

    start = time.perf_counter()
    result = get_ocr().ocr(image_array, cls=False)
    lines = []
    # PaddleOCR 2.x returns one list per input image, or [None] when empty.
    for page in result or []:
        for item in page or []:
            text, confidence = item[1]
            lines.append({"text": text, "confidence": float(confidence)})
    return {
        "text": "\n".join(line["text"] for line in lines),
        "lines": lines,
        "took_ms": round((time.perf_counter() - start) * 1000, 2),
    }


@app.post("/recognize")
async def recognize(request: Request):
    data = await read_image(request)
    return await run_in_threadpool(recognize_image, data)
