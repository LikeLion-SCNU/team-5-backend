"""내일은행 백엔드 — 파이프라인 검증용 플레이스홀더. 실제 구현으로 교체하세요."""
import os
from fastapi import FastAPI

app = FastAPI(title="naeil-bank-api")

@app.get("/health")
def health():
    return {"service": "naeil-bank-api", "db": os.getenv("DB_NAME", "unknown"), "status": "ok"}
