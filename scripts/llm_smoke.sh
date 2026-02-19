#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${OLLAMA_BASE_URL:-http://localhost:${OLLAMA_PORT:-11434}}"
EMBED_MODEL="${EMBEDDING_OLLAMA_MODEL:-bge-m3}"
LLM_MODEL="${LLM_OLLAMA_MODEL:-llama3.1:8b-instruct}"

log() {
  printf '[llm-smoke] %s\n' "$1"
}

fail() {
  printf '[llm-smoke][ERROR] %s\n' "$1" >&2
  exit 1
}

log "Checking Ollama runtime: ${BASE_URL}"
if ! curl -fsS "${BASE_URL}/api/tags" >/tmp/ollama_tags.json 2>/tmp/ollama_tags.err; then
  fail "Ollama is unreachable. Start it with: docker compose --profile llm up -d ollama"
fi
log "Ollama runtime is reachable"

if ! rg -q "${EMBED_MODEL%%:*}" /tmp/ollama_tags.json; then
  fail "Embedding model '${EMBED_MODEL}' not found. Run: docker compose --profile llm exec ollama ollama pull ${EMBED_MODEL}"
fi
if ! rg -q "${LLM_MODEL%%:*}" /tmp/ollama_tags.json; then
  fail "LLM model '${LLM_MODEL}' not found. Run: docker compose --profile llm exec ollama ollama pull ${LLM_MODEL}"
fi

log "Calling embedding endpoint (/api/embed)"
if ! curl -fsS "${BASE_URL}/api/embed" \
  -H 'Content-Type: application/json' \
  -d "{\"model\":\"${EMBED_MODEL}\",\"input\":[\"오토독 트리 임베딩 스모크\"]}" >/tmp/ollama_embed.json 2>/tmp/ollama_embed.err; then
  fail "Embedding request failed. Check model '${EMBED_MODEL}' and Ollama logs."
fi
if ! rg -q '"embeddings"|"embedding"' /tmp/ollama_embed.json; then
  fail "Embedding response is invalid. Expected 'embeddings' field."
fi
log "Embedding call succeeded"

log "Calling generate endpoint (/api/generate, stream=false)"
if ! curl -fsS "${BASE_URL}/api/generate" \
  -H 'Content-Type: application/json' \
  -d "{\"model\":\"${LLM_MODEL}\",\"prompt\":\"오프라인 스모크 테스트 확인 문장을 한 줄로 출력하세요.\",\"stream\":false}" >/tmp/ollama_generate.json 2>/tmp/ollama_generate.err; then
  fail "Generate request failed. Check model '${LLM_MODEL}' and Ollama logs."
fi
if ! rg -q '"response"' /tmp/ollama_generate.json; then
  fail "Generate response is invalid. Expected 'response' field."
fi
log "Generate call succeeded"

log "Smoke check completed successfully"
