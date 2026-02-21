#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${OPENSEARCH_URL:-http://localhost:59200}"
TEMPLATE_NAME="${1:-${OPENSEARCH_TEMPLATE_NAME:-docs-template-v2}}"
INDEX_PREFIX="${OPENSEARCH_INDEX_PREFIX:-docs}"
INDEX_VERSION="${OPENSEARCH_INDEX_VERSION:-v2}"
VECTOR_FIELD="${OPENSEARCH_VECTOR_FIELD:-doc_embedding}"
VECTOR_DIM="${OPENSEARCH_VECTOR_DIMENSION:-}"
INDEX_PATTERN="${INDEX_PREFIX}-${INDEX_VERSION}-*"

AUTH_ARGS=()
if [[ -n "${OPENSEARCH_USERNAME:-}" && -n "${OPENSEARCH_PASSWORD:-}" ]]; then
  AUTH_ARGS=(-u "${OPENSEARCH_USERNAME}:${OPENSEARCH_PASSWORD}")
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required" >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required" >&2
  exit 1
fi

request() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  if [[ -z "$body" ]]; then
    curl -sS "${AUTH_ARGS[@]}" -X "$method" "$BASE_URL$path" -H 'Content-Type: application/json'
  else
    curl -sS "${AUTH_ARGS[@]}" -X "$method" "$BASE_URL$path" -H 'Content-Type: application/json' -d "$body"
  fi
}

plugins_json="$(request GET '/_cat/plugins?format=json&h=component' || echo '[]')"
if ! echo "$plugins_json" | jq . >/dev/null 2>&1; then
  plugins_json='[]'
fi

has_icu="$(echo "$plugins_json" | jq -r 'map(.component // "" | ascii_downcase | contains("icu")) | any')"
has_nori="$(echo "$plugins_json" | jq -r 'map(.component // "" | ascii_downcase | contains("nori")) | any')"
has_knn="$(echo "$plugins_json" | jq -r 'map(.component // "" | ascii_downcase | contains("knn")) | any')"

std_tokenizer="standard"
fold_filter="asciifolding"
if [[ "$has_icu" == "true" ]]; then
  std_tokenizer="icu_tokenizer"
  fold_filter="icu_folding"
fi

use_nori=false
if [[ "$has_nori" == "true" || "$has_nori" == "" ]]; then
  use_nori=true
fi

use_knn=false
if [[ "$has_knn" == "true" && -n "$VECTOR_DIM" ]]; then
  use_knn=true
fi

ko_index_analyzer="std_index"
ko_search_analyzer="std_search"
if [[ "$use_nori" == "true" ]]; then
  ko_index_analyzer="ko_nori"
  ko_search_analyzer="ko_nori"
fi

echo "[template-v2] template=$TEMPLATE_NAME pattern=$INDEX_PATTERN"
echo "[template-v2] icu=$has_icu nori=$use_nori knn=$use_knn vector_dim=${VECTOR_DIM:-none}"

base_payload="$(jq -n \
  --arg index_pattern "$INDEX_PATTERN" \
  --arg std_tokenizer "$std_tokenizer" \
  --arg fold_filter "$fold_filter" \
  --arg ko_index_analyzer "$ko_index_analyzer" \
  --arg ko_search_analyzer "$ko_search_analyzer" \
  '{
    index_patterns: [$index_pattern],
    template: {
      settings: {
        number_of_shards: 1,
        number_of_replicas: 0,
        "index.max_ngram_diff": 19,
        analysis: {
          tokenizer: {
            ko_nori_tokenizer: {
              type: "nori_tokenizer",
              decompound_mode: "mixed"
            }
          },
          filter: {
            edge_ngram_filter: {
              type: "edge_ngram",
              min_gram: 1,
              max_gram: 20
            },
            ko_nori_pos_filter: {
              type: "nori_part_of_speech",
              stoptags: ["E", "IC", "J", "MAG", "MAJ", "MM", "SP", "SSC", "SSO", "SC", "SE", "XPN", "XSA", "XSN", "XSV", "UNA", "NA", "VSV"]
            },
            ko_nori_readingform: {
              type: "nori_readingform"
            }
          },
          analyzer: {
            std_index: {
              type: "custom",
              tokenizer: $std_tokenizer,
              filter: ["lowercase", $fold_filter]
            },
            std_search: {
              type: "custom",
              tokenizer: $std_tokenizer,
              filter: ["lowercase", $fold_filter]
            },
            autocomplete_index: {
              type: "custom",
              tokenizer: $std_tokenizer,
              filter: ["lowercase", $fold_filter, "edge_ngram_filter"]
            },
            autocomplete_search: {
              type: "custom",
              tokenizer: $std_tokenizer,
              filter: ["lowercase", $fold_filter]
            },
            ko_nori: {
              type: "custom",
              tokenizer: "ko_nori_tokenizer",
              filter: ["lowercase", "ko_nori_readingform", "ko_nori_pos_filter"]
            }
          }
        }
      },
      mappings: {
        properties: {
          workspace_id: { type: "keyword" },
          document_id: { type: "keyword" },
          title: {
            type: "text",
            analyzer: "std_index",
            search_analyzer: "std_search",
            fields: {
              ko: { type: "text", analyzer: $ko_index_analyzer, search_analyzer: $ko_search_analyzer },
              std: { type: "text", analyzer: "std_index", search_analyzer: "std_search" },
              edge: { type: "text", analyzer: "autocomplete_index", search_analyzer: "autocomplete_search" },
              keyword: { type: "keyword", ignore_above: 512 }
            }
          },
          body: {
            type: "text",
            analyzer: "std_index",
            search_analyzer: "std_search",
            fields: {
              ko: { type: "text", analyzer: $ko_index_analyzer, search_analyzer: $ko_search_analyzer },
              std: { type: "text", analyzer: "std_index", search_analyzer: "std_search" }
            }
          },
          created_at: { type: "date" },
          updated_at: { type: "date" }
        }
      }
    }
  }'
)"

payload="$base_payload"
if [[ "$use_nori" != "true" ]]; then
  payload="$(echo "$payload" | jq 'del(
    .template.settings.analysis.tokenizer.ko_nori_tokenizer,
    .template.settings.analysis.filter.ko_nori_pos_filter,
    .template.settings.analysis.filter.ko_nori_readingform,
    .template.settings.analysis.analyzer.ko_nori
  )')"
fi
if [[ "$use_knn" == "true" ]]; then
  payload="$(echo "$payload" | jq \
    --arg field "$VECTOR_FIELD" \
    --argjson dim "$VECTOR_DIM" \
    '.template.settings += {"knn": true, "index.knn": true}
     | .template.mappings.properties[$field] = {
         type: "knn_vector",
         dimension: $dim,
         method: {
           name: "hnsw",
           space_type: "cosinesimil",
           engine: "nmslib",
           parameters: { ef_construction: 128, m: 24 }
         }
       }'
  )"
fi

response="$(request PUT "/_index_template/$TEMPLATE_NAME" "$payload")"

echo "$response" | jq .
