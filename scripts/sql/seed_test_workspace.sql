\set ON_ERROR_STOP on

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM users WHERE email = 'owner@autodoc.local') THEN
        RAISE EXCEPTION 'owner@autodoc.local is missing. Start doc-api once to bootstrap seed users.';
    END IF;
END $$;

INSERT INTO workspaces (id, name, created_by, created_at)
SELECT
    '8f70d9bb-9e5b-4c48-b6a6-9f2755899c11',
    'Test',
    u.id,
    NOW()
FROM users u
WHERE u.email = 'owner@autodoc.local'
  AND NOT EXISTS (
    SELECT 1
    FROM workspaces w
    WHERE w.name = 'Test'
      AND w.created_by = u.id
);

INSERT INTO memberships (workspace_id, user_id, role, created_at)
SELECT
    w.id,
    u.id,
    'OWNER',
    NOW()
FROM users u
JOIN workspaces w
  ON w.created_by = u.id
WHERE u.email = 'owner@autodoc.local'
  AND w.name = 'Test'
ON CONFLICT (workspace_id, user_id) DO UPDATE
SET role = EXCLUDED.role;

DROP TABLE IF EXISTS tmp_seed_workspace;
CREATE TEMP TABLE tmp_seed_workspace AS
SELECT
    w.id AS workspace_id,
    u.id AS owner_id
FROM users u
JOIN workspaces w
  ON w.created_by = u.id
WHERE u.email = 'owner@autodoc.local'
  AND w.name = 'Test'
LIMIT 1;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM tmp_seed_workspace) THEN
        RAISE EXCEPTION 'Test workspace lookup failed after insert.';
    END IF;
END $$;

DROP TABLE IF EXISTS tmp_seed_documents;
CREATE TEMP TABLE tmp_seed_documents (
    slug TEXT PRIMARY KEY,
    id VARCHAR(36) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body_markdown TEXT NOT NULL,
    parent_slug TEXT NULL
) ON COMMIT DROP;

INSERT INTO tmp_seed_documents(slug, id, title, body_markdown, parent_slug) VALUES
('ai-architecture', '5c91b9c4-0af4-4d63-beb8-001d4f3a1011', 'AI 서비스 아키텍처 개요', '# AI 서비스 아키텍처
- 핵심 키워드: LLM, RAG, 임베딩, 벡터 검색, 재랭킹
- 목적: 문서 검색 품질을 높이고 자동 분류 파이프라인을 안정화한다.', NULL),
('ai-prompt-checklist', '31b3d39a-88ad-4a8b-9c1d-001d4f3a1012', '프롬프트 설계 체크리스트', '# 프롬프트 설계 체크리스트
질문 의도, 제약 조건, 출력 포맷, 근거 요구 여부를 점검한다.
LLM 응답 품질 개선을 위한 반복 실험 기록.', 'ai-architecture'),
('ai-rag-pipeline', '4f4a5c86-f1bc-4f8b-9973-001d4f3a1013', 'RAG 검색 파이프라인', '# RAG 파이프라인
BM25 + Vector Hybrid 검색, RRF 결합, workspace 필터를 일관되게 적용한다.
OpenSearch alias swap 절차도 포함한다.', 'ai-architecture'),
('ai-vector-tuning', '993f09b2-3f8a-4b5f-9c37-001d4f3a1014', '벡터 검색 성능 튜닝', '# Vector 튜닝
HNSW 파라미터와 embedding 차원 점검.
query latency와 recall 균형을 맞춘다.', NULL),
('ai-release-checklist', '56d2422d-b03f-4fd5-9cb6-001d4f3a1015', 'AI 릴리스 체크리스트', '# 릴리스 체크리스트
인덱스 마이그레이션, 롤백 절차, smoke test를 사전에 검증한다.', 'ai-architecture'),

('travel-tokyo', 'e7c5f72f-1cae-4f3b-a841-001d4f3a2011', '도쿄 출장 가이드', '# 도쿄 출장 가이드
교통, 숙소, 일정, 회의 동선 관리.
업무 일정과 여행 일정의 균형을 맞춘다.', NULL),
('travel-tokyo-food', '158d72cf-1bd0-45b4-a5f5-001d4f3a2012', '도쿄 출장 가이드 - 맛집', '# 도쿄 맛집
스시, 라멘, 카페 후보를 지도 기반으로 정리.
출장 중 점심 동선에 맞춘 식당 리스트.', 'travel-tokyo'),
('travel-tokyo-budget', '67cb9db3-9d1d-4ad0-b8de-001d4f3a2013', '도쿄 출장 가이드 - 예산', '# 출장 예산
항공, 숙박, 식비, 교통비를 항목별로 관리한다.', 'travel-tokyo'),
('travel-jeju', '7df34f6b-257f-4d67-b909-001d4f3a2014', '제주 가족 여행 체크리스트', '# 제주 여행
렌터카, 숙소, 일정, 아이 동반 준비물 목록.', NULL),
('travel-carry-on', '899ae813-3afb-4bdc-8eb5-001d4f3a2015', '여행 캐리어 준비물', '# 캐리어 준비물
전자기기, 상비약, 세면도구, 여권 사본을 사전 점검한다.', 'travel-jeju'),

('health-running-plan', '9b7d16cb-ef58-45fe-a9df-001d4f3a3011', '러닝 훈련 계획', '# 러닝 훈련
주 4회 인터벌과 롱런 루틴으로 지구력을 향상한다.', NULL),
('health-running-recovery', 'af29e171-cd4f-4d90-9705-001d4f3a3012', '러닝 훈련 계획 - 회복 루틴', '# 회복 루틴
폼롤러, 스트레칭, 수분 보충으로 부상 위험을 줄인다.', 'health-running-plan'),
('health-sleep-log', 'd24db96d-2f43-4b95-9e0f-001d4f3a3013', '수면 개선 실험 노트', '# 수면 실험
취침 시간, 카페인 섭취량, 수면 점수를 기록하고 상관관계를 본다.', 'health-running-plan'),
('health-diet', '40ed76e8-a9ed-457f-a4f2-001d4f3a3014', '식단 관리 메모', '# 식단 관리
단백질, 탄수화물, 지방 비율을 일일 단위로 기록한다.', NULL),

('finance-plan', '6f2dd79e-68c1-4a79-90be-001d4f3a4011', '개인 재무 계획 2026', '# 재무 계획
연간 현금흐름, 비상금, 투자 비중을 설계한다.', NULL),
('finance-monthly-budget', 'f1f8be1e-ef0e-4cca-8a65-001d4f3a4012', '개인 재무 계획 2026 - 월간 예산', '# 월간 예산
고정비, 변동비, 저축 목표를 월별로 관리한다.', 'finance-plan'),
('finance-etf', '4c46b4f4-5d98-4f09-9f58-001d4f3a4013', 'ETF 포트폴리오 리밸런싱', '# ETF 리밸런싱
국내외 ETF 비중을 분기마다 조정하고 리스크를 관리한다.', 'finance-plan'),
('finance-tax', '04b27f6f-5188-4332-b3a5-001d4f3a4014', '연말정산 체크포인트', '# 세금 체크
공제 항목, 증빙 서류, 신고 일정 체크리스트.', NULL),

('conan-series', '2d4f581a-4e2a-4fa8-9a5f-001d4f3a5011', '코난 시리즈 정리', '# 코난 시리즈
코난, 탐정, 사건, 단서 중심으로 에피소드 요약.', NULL),
('conan-vol1', 'eb36207b-57d0-4ace-b080-001d4f3a5012', '코난 전기 1', '# 코난 전기 1
첫 사건, 단서 추적, 범인 동기를 정리한다.', 'conan-series'),
('conan-vol2', '6d082f42-15f8-4bce-bb31-001d4f3a5013', '코난 전기 2', '# 코난 전기 2
탐정 추리 과정과 반전 포인트를 기록한다.', 'conan-series'),
('conan-vol3', 'e0afdfad-1107-4cc9-b178-001d4f3a5014', '코난 전기 3', '# 코난 전기 3
장기 사건의 연결 구조와 핵심 증거를 정리한다.', 'conan-series'),

('tea-lab', '6aa7740f-2f4f-4e42-8ba5-001d4f3a6011', '차 연구 노트', '# 차 연구
녹차, 홍차, 우롱차 향미 비교와 추출 조건을 기록한다.', NULL),
('tea-green', '5f19f5dc-2f5b-4b66-aa91-001d4f3a6012', '녹차의 효능', '# 녹차
카테킨, 집중력, 카페인 반응에 대한 메모.', 'tea-lab'),
('tea-black', 'fc10b0ce-a6bd-4fe8-aa09-001d4f3a6013', '홍차 추출 실험', '# 홍차
우림 시간별 향미 변화와 떫은맛 지표를 정리한다.', 'tea-lab'),
('tea-cafe', '0ec8cdf7-3706-4593-a85d-001d4f3a6014', '찻집 탐방 기록', '# 찻집 탐방
지역별 찻집 분위기와 메뉴 특성을 비교한다.', 'tea-lab'),

('science-history', '3e5fd08f-9f34-4f4a-9691-001d4f3a7011', '과학의 역사', '# 과학의 역사
고전 물리학부터 현대 과학까지 주요 전환점을 정리한다.', NULL),
('science-social-context', '0d5f9ed5-4c6d-4bb5-9be1-001d4f3a7012', '사회적인 맥락의 과학', '# 사회와 과학
과학 지식이 정책과 산업에 미치는 영향을 사례로 정리한다.', 'science-history'),
('science-space', '95f9df4b-5b75-4e24-95e6-001d4f3a7013', '우주 탐사 연표', '# 우주 탐사
로켓 기술, 탐사선 임무, 우주 과학 성과를 연도별로 기록한다.', 'science-history'),

('misc-upload-host-check', '7b2333f1-0b5f-4db8-a2bd-001d4f3a8011', 'upload-host-check', '# 업로드 호스트 점검
스토리지 연결, presign URL, 업로드 완료 이벤트 점검.', NULL),
('misc-meeting-template', '34f40ec2-a66d-4f7e-a90b-001d4f3a8012', '주간 회의록 템플릿', '# 주간 회의록 템플릿
안건, 결정사항, 액션 아이템, 담당자, 마감일 양식.', NULL);

WITH resolved_docs AS (
    SELECT
        d.id,
        d.title,
        d.body_markdown,
        p.id AS parent_document_id
    FROM tmp_seed_documents d
    LEFT JOIN tmp_seed_documents p
      ON p.slug = d.parent_slug
)
INSERT INTO documents(
    id,
    workspace_id,
    title,
    body_markdown,
    body_text,
    parent_document_id,
    source_type,
    status,
    version,
    deleted,
    created_by,
    created_at,
    updated_at
)
SELECT
    r.id,
    ws.workspace_id,
    r.title,
    r.body_markdown,
    r.body_markdown,
    r.parent_document_id,
    'EDITOR',
    'PROCESSING',
    0,
    FALSE,
    ws.owner_id,
    NOW(),
    NOW()
FROM resolved_docs r
CROSS JOIN tmp_seed_workspace ws
ON CONFLICT (id) DO UPDATE
SET
    workspace_id = EXCLUDED.workspace_id,
    title = EXCLUDED.title,
    body_markdown = EXCLUDED.body_markdown,
    body_text = EXCLUDED.body_text,
    parent_document_id = EXCLUDED.parent_document_id,
    source_type = EXCLUDED.source_type,
    deleted = FALSE,
    updated_at = NOW();

INSERT INTO pipeline_status(
    workspace_id,
    document_id,
    ingest_status,
    embed_status,
    index_status,
    tree_status,
    failure_reason,
    updated_at
)
SELECT
    ws.workspace_id,
    d.id,
    'PENDING',
    'PENDING',
    'PENDING',
    'PENDING',
    NULL,
    NOW()
FROM tmp_seed_documents d
CROSS JOIN tmp_seed_workspace ws
ON CONFLICT (workspace_id, document_id) DO NOTHING;

INSERT INTO outbox_event(
    id,
    workspace_id,
    document_id,
    event_type,
    payload_json,
    status,
    retry_count,
    available_at,
    created_at,
    updated_at
)
SELECT
    md5('seed-docsaved:' || ws.workspace_id || ':' || d.id),
    ws.workspace_id,
    d.id,
    'DocumentSaved',
    json_build_object(
        'document_id', d.id,
        'source_type', 'EDITOR',
        'seed', 'test-workspace'
    )::text,
    'PENDING',
    0,
    NOW(),
    NOW(),
    NOW()
FROM tmp_seed_documents d
CROSS JOIN tmp_seed_workspace ws
WHERE NOT EXISTS (
    SELECT 1
    FROM outbox_event oe
    WHERE oe.workspace_id = ws.workspace_id
      AND oe.document_id = d.id
      AND oe.event_type = 'DocumentSaved'
);

SELECT
    ws.workspace_id,
    (SELECT COUNT(*) FROM documents d WHERE d.workspace_id = ws.workspace_id AND d.deleted = FALSE) AS document_count,
    (SELECT COUNT(*) FROM documents d WHERE d.workspace_id = ws.workspace_id AND d.deleted = FALSE AND d.parent_document_id IS NOT NULL) AS child_document_count,
    (SELECT COUNT(*) FROM outbox_event oe WHERE oe.workspace_id = ws.workspace_id AND oe.event_type = 'DocumentSaved' AND oe.status IN ('PENDING', 'RETRY', 'PROCESSING')) AS pending_docsaved_events
FROM tmp_seed_workspace ws;

COMMIT;
