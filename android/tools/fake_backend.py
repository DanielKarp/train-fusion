"""The recorder API (../../backend) on this machine, with its in-memory fakes (backend/api/ports.py)
in place of Firestore and Identity Platform, and a fake bucket that takes the signed PUTs the
way GCS does (exact size, create-only: 412 if the object is there). For trying the Android
app's backend client without GCP:

    pip install fastapi uvicorn        # (backend/requirements.txt pins them)
    python android/tools/fake_backend.py [port]

Account tokens are the fakes' "token:<uid>:<email>". Nothing is kept when it stops.
"""
from __future__ import annotations

import sys
from datetime import timedelta
from pathlib import Path
from urllib.parse import quote, unquote

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "backend"))

import uvicorn  # noqa: E402
from fastapi import Request, Response  # noqa: E402

from api.app import Deps, create_app  # noqa: E402
from api.config import Settings  # noqa: E402
from api.ports import MemoryDocs, MemoryIdentity  # noqa: E402

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
BASE = f"http://127.0.0.1:{PORT}"


class LocalBlobs:
    """Signed URLs that point back at this server, which checks the signed headers."""

    def __init__(self) -> None:
        self.objects: dict[str, int] = {}
        self.required: dict[str, dict[str, str]] = {}

    def sizes(self, prefix: str) -> dict[str, int]:
        return {n: s for n, s in self.objects.items() if n.startswith(prefix)}

    def signed_put_url(self, name: str, headers: dict[str, str], expiration: timedelta) -> str:
        self.required[name] = dict(headers)
        return f"{BASE}/_bucket/{quote(name)}?X-Goog-Signature=fake"


blobs = LocalBlobs()
app = create_app(Deps(settings=Settings(project="test-project", recordings_bucket="test-bucket",
                                        contact_email="contact@example.com"),
                      docs=MemoryDocs(), blobs=blobs, identity=MemoryIdentity()))


@app.put("/_bucket/{name:path}", include_in_schema=False)
async def put_object(name: str, request: Request) -> Response:
    name = unquote(name)
    required = blobs.required.get(name)
    if required is None:
        return Response("no such signed URL", status_code=403)
    for header, value in required.items():
        if header.lower() in ("x-goog-content-length-range",):
            continue
        if request.headers.get(header) != value:
            return Response(f"header {header} must be {value!r}", status_code=403)
    body = await request.body()
    lo, hi = (int(x) for x in required["x-goog-content-length-range"].split(","))
    if not lo <= len(body) <= hi:
        return Response(f"size {len(body)} not in {lo}..{hi}", status_code=400)
    if name in blobs.objects:
        return Response("already there", status_code=412)
    blobs.objects[name] = len(body)
    return Response(status_code=200)


@app.get("/_bucket", include_in_schema=False)
def list_objects() -> dict:
    return {"objects": blobs.objects}


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=PORT, log_level="warning")
