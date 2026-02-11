from __future__ import annotations

import math
import os
import re
from dataclasses import dataclass
from typing import List

from fastapi import FastAPI
from pydantic import BaseModel, Field

TOKEN_SPLIT = re.compile(r"[^\w\u3131-\u318E\uAC00-\uD7A3]+")


class PairRequestItem(BaseModel):
    pair_key: str = Field(min_length=1)
    left_text: str = Field(min_length=1)
    right_text: str = Field(min_length=1)


class RerankPairsRequest(BaseModel):
    pairs: List[PairRequestItem] = Field(default_factory=list, min_length=1, max_length=256)


class PairResponseItem(BaseModel):
    pair_key: str
    score: float


class RerankPairsResponse(BaseModel):
    items: List[PairResponseItem]


@dataclass(frozen=True)
class LexicalReranker:
    min_token_len: int = 2

    def score(self, left: str, right: str) -> float:
        left_tokens = self._tokenize(left)
        right_tokens = self._tokenize(right)
        if not left_tokens or not right_tokens:
            return 0.0

        left_set = set(left_tokens)
        right_set = set(right_tokens)
        intersection = left_set & right_set
        union = left_set | right_set

        overlap = len(intersection) / max(1.0, float(min(len(left_set), len(right_set))))
        jaccard = len(intersection) / max(1.0, float(len(union)))

        left_tf = self._term_frequency(left_tokens)
        right_tf = self._term_frequency(right_tokens)
        cosine = self._cosine_sparse(left_tf, right_tf)

        prefix_bonus = 0.08 if left_tokens[0] == right_tokens[0] else 0.0
        score = (overlap * 0.45) + (jaccard * 0.25) + (cosine * 0.30) + prefix_bonus
        return max(0.0, min(1.0, score))

    def _tokenize(self, text: str) -> List[str]:
        normalized = TOKEN_SPLIT.sub(" ", text.lower()).strip()
        if not normalized:
            return []
        return [token for token in normalized.split() if len(token) >= self.min_token_len][:160]

    def _term_frequency(self, tokens: List[str]) -> dict[str, float]:
        freq: dict[str, float] = {}
        for token in tokens:
            freq[token] = freq.get(token, 0.0) + 1.0
        if not freq:
            return freq
        max_tf = max(freq.values())
        return {token: value / max_tf for token, value in freq.items()}

    def _cosine_sparse(self, left: dict[str, float], right: dict[str, float]) -> float:
        if not left or not right:
            return 0.0
        dot = 0.0
        for token, value in left.items():
            dot += value * right.get(token, 0.0)
        left_norm = math.sqrt(sum(value * value for value in left.values()))
        right_norm = math.sqrt(sum(value * value for value in right.values()))
        if left_norm == 0.0 or right_norm == 0.0:
            return 0.0
        return dot / (left_norm * right_norm)


app = FastAPI(title="autodoctree-reranker", version="0.1.0")
reranker = LexicalReranker()
backend = os.getenv("RERANKER_BACKEND", "lexical")
model_path = os.getenv("RERANKER_MODEL_PATH", "")


@app.get("/health")
def health() -> dict[str, object]:
    return {
        "status": "ok",
        "backend": backend,
        "model_path": model_path or None,
    }


@app.post("/v1/rerank/pairs", response_model=RerankPairsResponse)
def rerank_pairs(payload: RerankPairsRequest) -> RerankPairsResponse:
    items = [
        PairResponseItem(
            pair_key=item.pair_key,
            score=reranker.score(item.left_text, item.right_text),
        )
        for item in payload.pairs
    ]
    return RerankPairsResponse(items=items)
