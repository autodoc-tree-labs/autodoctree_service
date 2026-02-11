# Execution Order — AutoDoc Tree v12.1

Generated: 2026-02-11

This pack focuses on **offline/local models** and **systematic clustering quality**.
Recommended order (min-risk):

## Phase 1 — Make it observable (must)
1. B-0101 Tree Rebuild Telemetry v1
2. I-0101 Local dashboards for Tree
3. B-0102 Tree Debug API v1
4. A-0102 Admin Debug Console v1

## Phase 2 — Use real embeddings (offline)
5. I-0201 Ollama Embedding Setup (bge-m3) — bootstrap
6. B-0201 Embedding Targets v2 (TITLE/BODY/SECTION_CENTROID)
7. B-0202 Embedding Input Quality Scoring & noise filters

## Phase 3 — Fix graph quality (stop mixing)
8. B-0401 Neighbor Graph v3 (mutual-kNN + SNN)
9. B-0402 Bridge & Hub Quarantine v2
10. B-0301 Korean Lexical v2 (Nori tokenizer)

## Phase 4 — Policy + trust + UX loop
11. B-0802 Explain v2 + U-0802 Explain Drawer
12. B-0502 Calibration & Selective Automation + A-0502 controls
13. U-0302 Inbox/Template UX + U-0402 confidence UI
14. B-0901 Rules + A-0901 Rule UI
15. B-0902 Active Learning Queue + U-0902 Smart Questions + A-0902 analytics

## Phase 5 — Optional precision boosters
16. I-0501 Local Reranker service + B-0501 Reranker gate

## Phase 6 — Optional advanced structure
17. B-0601 Consensus clustering
18. I-0602 hSBM worker + B-0602 import
19. B-0701 Concept prototypes + B-0702 Tree optimizer
20. B-0801 Multi-view trees + U-0801 UI

## Phase 7 — Hardening
21. B-1101 Multi-tenancy hardening
22. I-1101 Security baseline
23. A-1101 Audit logs
