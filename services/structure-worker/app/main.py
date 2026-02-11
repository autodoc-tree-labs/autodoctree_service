from __future__ import annotations

from collections import defaultdict, deque
from statistics import mean
from typing import Dict, List, Set, Tuple

from fastapi import FastAPI
from pydantic import BaseModel, Field


class DocumentItem(BaseModel):
    id: str = Field(min_length=1)
    title: str = Field(default="")


class EdgeItem(BaseModel):
    left: str = Field(min_length=1)
    right: str = Field(min_length=1)
    weight: float = Field(ge=0.0, le=1.0)


class StructureInferRequest(BaseModel):
    documents: List[DocumentItem] = Field(default_factory=list, min_length=1, max_length=5000)
    edges: List[EdgeItem] = Field(default_factory=list)
    max_depth: int = Field(default=3, ge=2, le=5)


class ClusterItem(BaseModel):
    id: str
    document_ids: List[str]
    quality_score: float


class StructureInferResponse(BaseModel):
    clusters: List[ClusterItem]


app = FastAPI(title="autodoctree-structure-worker", version="0.1.0")


@app.get("/health")
def health() -> Dict[str, object]:
    return {"status": "ok", "worker": "heuristic-hsbm-fallback"}


@app.post("/v1/structure/infer", response_model=StructureInferResponse)
def infer_structure(payload: StructureInferRequest) -> StructureInferResponse:
    doc_ids = [doc.id for doc in payload.documents]
    adjacency, edge_weights = _build_adjacency(doc_ids, payload.edges)
    components = _connected_components(doc_ids, adjacency)

    clusters: List[ClusterItem] = []
    for index, component in enumerate(sorted(components, key=lambda item: (-len(item), item))):
        score = _cluster_quality(component, edge_weights)
        clusters.append(
            ClusterItem(
                id=f"worker-cluster-{index + 1}",
                document_ids=component,
                quality_score=score,
            )
        )

    if not clusters:
        clusters = [
            ClusterItem(
                id=f"worker-cluster-{index + 1}",
                document_ids=[doc_id],
                quality_score=0.0,
            )
            for index, doc_id in enumerate(sorted(doc_ids))
        ]

    return StructureInferResponse(clusters=clusters)


def _build_adjacency(doc_ids: List[str], edges: List[EdgeItem]) -> Tuple[Dict[str, Set[str]], Dict[str, float]]:
    adjacency: Dict[str, Set[str]] = {doc_id: set() for doc_id in doc_ids}
    edge_weights: Dict[str, float] = {}

    for edge in edges:
        if edge.left == edge.right:
            continue
        if edge.left not in adjacency or edge.right not in adjacency:
            continue
        if edge.weight < 0.45:
            continue

        key = _pair_key(edge.left, edge.right)
        edge_weights[key] = max(edge_weights.get(key, 0.0), edge.weight)
        adjacency[edge.left].add(edge.right)
        adjacency[edge.right].add(edge.left)

    return adjacency, edge_weights


def _connected_components(doc_ids: List[str], adjacency: Dict[str, Set[str]]) -> List[List[str]]:
    visited: Set[str] = set()
    components: List[List[str]] = []

    for doc_id in sorted(doc_ids):
        if doc_id in visited:
            continue
        queue = deque([doc_id])
        visited.add(doc_id)
        component: List[str] = []

        while queue:
            current = queue.popleft()
            component.append(current)
            for neighbor in sorted(adjacency.get(current, set())):
                if neighbor in visited:
                    continue
                visited.add(neighbor)
                queue.append(neighbor)

        components.append(sorted(component))

    return components


def _cluster_quality(document_ids: List[str], edge_weights: Dict[str, float]) -> float:
    if len(document_ids) <= 1:
        return 1.0

    weights: List[float] = []
    for index, left in enumerate(document_ids):
        for right in document_ids[index + 1 :]:
            key = _pair_key(left, right)
            if key in edge_weights:
                weights.append(edge_weights[key])

    if not weights:
        return 0.0

    return max(0.0, min(1.0, mean(weights)))


def _pair_key(left: str, right: str) -> str:
    return f"{left}::{right}" if left <= right else f"{right}::{left}"
