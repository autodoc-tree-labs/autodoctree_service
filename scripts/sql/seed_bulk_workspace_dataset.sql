\set ON_ERROR_STOP on

BEGIN;

CREATE OR REPLACE FUNCTION pg_temp.seed_uuid(seed text)
RETURNS VARCHAR
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    h TEXT;
BEGIN
    h := md5(seed);
    RETURN lower(
        substr(h, 1, 8) || '-' ||
        substr(h, 9, 4) || '-' ||
        substr(h, 13, 4) || '-' ||
        substr(h, 17, 4) || '-' ||
        substr(h, 21, 12)
    );
END;
$$;

DROP TABLE IF EXISTS tmp_bulk_owner;
CREATE TEMP TABLE tmp_bulk_owner ON COMMIT DROP AS
SELECT id AS owner_id
FROM users
WHERE email = :'seed_owner_email'
LIMIT 1;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM tmp_bulk_owner) THEN
        RAISE EXCEPTION 'seed owner not found. run doc-api once to bootstrap users.';
    END IF;
END $$;

INSERT INTO workspaces (id, name, created_by, created_at)
SELECT
    :'seed_workspace_id',
    :'seed_workspace_name',
    o.owner_id,
    NOW()
FROM tmp_bulk_owner o
ON CONFLICT (id) DO UPDATE
SET name = EXCLUDED.name;

INSERT INTO memberships (workspace_id, user_id, role, created_at)
SELECT
    :'seed_workspace_id',
    o.owner_id,
    'OWNER',
    NOW()
FROM tmp_bulk_owner o
ON CONFLICT (workspace_id, user_id) DO UPDATE
SET role = EXCLUDED.role;

DROP TABLE IF EXISTS tmp_bulk_categories;
CREATE TEMP TABLE tmp_bulk_categories (
    category_idx INT PRIMARY KEY,
    category_key TEXT NOT NULL,
    category_label TEXT NOT NULL,
    keyword_a TEXT NOT NULL,
    keyword_b TEXT NOT NULL,
    keyword_c TEXT NOT NULL,
    file_ext TEXT NOT NULL,
    content_type TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_bulk_categories(
    category_idx, category_key, category_label, keyword_a, keyword_b, keyword_c, file_ext, content_type
) VALUES
    (1,  'ai-platform',         'AI 플랫폼',          'llm',           'rag',            'embedding',      'md',   'text/markdown'),
    (2,  'backend-api',         '백엔드 API',         'kotlin',        'spring',         'rest',           'json', 'application/octet-stream'),
    (3,  'frontend-web',        '프론트엔드',         'react',         'typescript',     'ui',             'png',  'image/png'),
    (4,  'devops-sre',          'DevOps/SRE',         'kubernetes',    'ci-cd',          'observability',  'yaml', 'application/octet-stream'),
    (5,  'security-gov',        '보안/거버넌스',      'iam',           'audit',          'policy',         'pdf',  'application/pdf'),
    (6,  'data-engineering',    '데이터 엔지니어링',  'etl',           'warehouse',      'batch',          'csv',  'text/csv'),
    (7,  'product-management',  '프로덕트 기획',      'roadmap',       'kpi',            'discovery',      'md',   'text/markdown'),
    (8,  'marketing-growth',    '마케팅/그로스',      'campaign',      'conversion',     'funnel',         'csv',  'text/csv'),
    (9,  'sales-ops',           '영업 운영',          'lead',          'crm',            'quota',          'xlsx', 'application/octet-stream'),
    (10, 'hr-people',           'HR/피플',            'recruiting',    'onboarding',     'evaluation',     'docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'),
    (11, 'finance-accounting',  '재무/회계',          'budget',        'cashflow',       'cost',           'xlsx', 'application/octet-stream'),
    (12, 'legal-compliance',    '법무/컴플라이언스',  'contract',      'compliance',     'risk',           'pdf',  'application/pdf'),
    (13, 'customer-support',    '고객지원',           'ticket',        'sla',            'escalation',     'txt',  'text/plain'),
    (14, 'travel-logistics',    '여행/물류',          'itinerary',     'route',          'booking',        'csv',  'text/csv'),
    (15, 'health-fitness',      '헬스/피트니스',      'nutrition',     'training',       'recovery',       'md',   'text/markdown'),
    (16, 'education-learning',  '교육/학습',          'curriculum',    'lesson',         'assessment',     'pdf',  'application/pdf'),
    (17, 'research-lab',        '연구/실험',          'hypothesis',    'experiment',     'analysis',       'ipynb','application/octet-stream'),
    (18, 'design-ux',           '디자인/UX',          'wireframe',     'prototype',      'usability',      'png',  'image/png'),
    (19, 'qa-testing',          'QA/테스트',          'testcase',      'regression',     'coverage',       'txt',  'text/plain'),
    (20, 'operations-admin',    '운영/총무',          'procurement',   'inventory',      'vendor',         'csv',  'text/csv'),
    (21, 'research-papers',     '논문/리서치',        'citation',      'benchmark',      'methodology',    'pdf',  'application/pdf'),
    (22, 'incident-review',     '장애 회고',          'postmortem',    'timeline',       'action-item',    'md',   'text/markdown'),
    (23, 'knowledge-base',      '지식베이스',         'faq',           'runbook',        'howto',          'txt',  'text/plain'),
    (24, 'project-portfolio',   '프로젝트 포트폴리오','milestone',     'scope',          'retrospective',  'pptx', 'application/octet-stream'),
    (25, 'climate-sustainability', '기후/지속가능성', 'carbon',      'esg',            'renewable',      'pdf',  'application/pdf'),
    (26, 'gaming-esports',      '게임/e스포츠',       'matchmaking',   'meta',           'latency',        'md',   'text/markdown'),
    (27, 'food-culinary',       '요리/식음료',        'recipe',        'kitchen',        'menu',           'txt',  'text/plain'),
    (28, 'media-content',       '콘텐츠/미디어',      'editorial',     'script',         'thumbnail',      'png',  'image/png'),
    (29, 'startup-investing',   '스타트업/투자',      'valuation',     'term-sheet',     'diligence',      'pdf',  'application/pdf'),
    (30, 'public-policy',       '공공정책',           'regulation',    'governance',     'citizen',        'docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'),
    (31, 'biotech-medical',     '바이오/메디컬',      'clinical',      'assay',          'protocol',       'pdf',  'application/pdf'),
    (32, 'manufacturing-iot',   '제조/IoT',           'sensor',        'plc',            'oee',            'csv',  'text/csv'),
    (33, 'mobility-auto',       '모빌리티/자동차',    'ev',            'telematics',     'safety',         'md',   'text/markdown'),
    (34, 'real-estate-space',   '부동산/공간',        'lease',         'occupancy',      'facility',       'pdf',  'application/pdf'),
    (35, 'creator-economy',     '크리에이터 이코노미', 'creator',      'sponsorship',    'ugc',            'csv',  'text/csv'),
    (36, 'nonprofit-social',    '비영리/소셜임팩트',   'donation',      'grant',          'impact',         'docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'),
    (37, 'academia-conference', '학회/컨퍼런스',       'submission',    'reviewer',       'presentation',   'pptx', 'application/octet-stream'),
    (38, 'language-localization', '언어/로컬라이제이션', 'i18n',      'translation',    'terminology',    'txt',  'text/plain'),
    (39, 'retail-commerce',     '리테일/커머스',      'sku',           'promotion',      'merchandising',  'csv',  'text/csv'),
    (40, 'cloud-finops',        '클라우드 FinOps',    'cloud-cost',    'rightsizing',    'savings',        'md',   'text/markdown');

DROP TABLE IF EXISTS tmp_bulk_roots;
CREATE TEMP TABLE tmp_bulk_roots ON COMMIT DROP AS
SELECT
    c.category_idx,
    c.category_key,
    c.category_label,
    c.keyword_a,
    c.keyword_b,
    c.keyword_c,
    pg_temp.seed_uuid('bulk-root:' || :'seed_workspace_id' || ':' || c.category_key) AS document_id,
    format('%s 허브', c.category_label) AS title,
    format(
        E'# %s 허브\n\n## 개요\n- 대표 키워드: %s, %s, %s\n- 목적: %s 관련 문서를 한곳에 모아 탐색성과 자동분류 품질을 점검한다.\n\n## 운영 원칙\n1. 문서 제목에 핵심 도메인을 명시한다.\n2. 본문에는 배경, 실행안, 위험요소를 포함한다.\n3. 관련 첨부를 추가해 검색/분류 신호를 강화한다.\n',
        c.category_label,
        c.keyword_a,
        c.keyword_b,
        c.keyword_c,
        c.category_label
    ) AS body_markdown
FROM tmp_bulk_categories c;

DROP TABLE IF EXISTS tmp_bulk_children;
CREATE TEMP TABLE tmp_bulk_children ON COMMIT DROP AS
WITH params AS (
    SELECT
        GREATEST(1, (:'seed_doc_count')::INT) AS doc_target,
        (SELECT COUNT(*) FROM tmp_bulk_categories) AS category_total
),
seq AS (
    SELECT generate_series(1, (SELECT doc_target FROM params)) AS seq_no
),
base AS (
    SELECT
        s.seq_no,
        ((s.seq_no - 1) % p.category_total) + 1 AS category_idx
    FROM seq s
    CROSS JOIN params p
),
enriched AS (
    SELECT
        b.seq_no,
        c.category_idx,
        c.category_key,
        c.category_label,
        c.keyword_a,
        c.keyword_b,
        c.keyword_c,
        c.file_ext,
        c.content_type,
        r.document_id AS root_document_id,
        pg_temp.seed_uuid('bulk-doc:' || :'seed_workspace_id' || ':' || b.seq_no::TEXT) AS document_id,
        ((b.seq_no - 1) / p.category_total) + 1 AS ordinal_in_category,
        (ARRAY['주간 운영', '월간 리뷰', '실험 기록', '개선 제안', '리스크 점검', '실행 계획', '고객 피드백', '성과 분석', '장애 예방', '프로세스 개선', '비용 최적화', '품질 개선'])[((b.seq_no + c.category_idx * 2) % 12) + 1] AS stage_label,
        (ARRAY['온보딩', '자동화', '가이드', '체크포인트', '런북', '플레이북', '실행안', '회고', '브리프', 'FAQ', '설정안', '템플릿', '정책안', '의사결정 로그', '비교 분석', '검증 노트'])[((b.seq_no * 3 + c.category_idx) % 16) + 1] AS artifact_label,
        (ARRAY['핵심지표', '사용자경험', '신뢰성', '보안성', '확장성', '협업', '효율화', '운영안정성', '성장전략', '리텐션', '품질보증', '비용절감', '자동화율', '거버넌스'])[((b.seq_no * 5 + c.category_idx) % 14) + 1] AS focus_label,
        (ARRAY['draft', 'review', 'pending', 'final', 'refactor', 'archive'])[((b.seq_no * 7 + c.category_idx) % 6) + 1] AS maturity_code,
        (ARRAY['초안', '검토중', '승인대기', '확정', '리팩토링 예정', '아카이브 후보'])[((b.seq_no * 7 + c.category_idx) % 6) + 1] AS maturity_label,
        (ARRAY['P0', 'P1', 'P2', 'P3'])[((b.seq_no + c.category_idx) % 4) + 1] AS priority_label
    FROM base b
    JOIN tmp_bulk_categories c ON c.category_idx = b.category_idx
    JOIN tmp_bulk_roots r ON r.category_idx = b.category_idx
    CROSS JOIN params p
),
with_parent AS (
    SELECT
        e.*,
        LAG(e.document_id) OVER (PARTITION BY e.category_idx ORDER BY e.seq_no) AS prev_document_id
    FROM enriched e
)
SELECT
    wp.seq_no,
    wp.category_idx,
    wp.category_key,
    wp.category_label,
    wp.keyword_a,
    wp.keyword_b,
    wp.keyword_c,
    wp.file_ext,
    wp.content_type,
    wp.stage_label,
    wp.artifact_label,
    wp.focus_label,
    wp.maturity_code,
    wp.maturity_label,
    wp.priority_label,
    wp.document_id,
    wp.ordinal_in_category,
    format('%s %s %s (%s)', wp.category_label, wp.focus_label, wp.artifact_label, wp.maturity_label) AS title,
    format(
        E'# %s %s %s (%s)\n\n> 우선순위: %s\n> 분류 키워드: %s, %s, %s\n\n## 배경\n%s 영역에서 %s 이슈를 중심으로 운영 상태를 점검하고 실행안을 정리한다.\n기존 문서/첨부와 연결해 분류 정확도와 탐색성을 높이는 것이 목표다.\n\n## 이번 문서 목표\n- %s 관점에서 현재 상태를 진단한다.\n- %s 기준으로 개선 우선순위를 도출한다.\n- %s 단계에서 필요한 실행 항목을 확정한다.\n\n## 실행 체크리스트\n- [ ] 현황 데이터 수집 및 결측치 점검\n- [ ] 핵심 이해관계자 일정 동기화\n- [ ] 대안별 비용/효과 비교표 작성\n- [ ] 변경 후 모니터링 임계치 확정\n\n## 참고 지표\n| 항목 | 값 |\n| --- | --- |\n| 문서 코드 | %s |\n| 분기 | Q%s |\n| 예상 영향도 | %s%% |\n| 첨부 필요 여부 | %s |\n\n## 리스크/대응\n1. %s 관련 병목이 재발할 수 있어 가드레일을 강화한다.\n2. %s 신호가 약한 경우 수동 검토 fallback을 유지한다.\n3. %s 변경점은 단계 배포로 확산 리스크를 낮춘다.\n\n## 다음 액션\n- 연계 문서 2건 추가 작성\n- 허브 페이지 요약 갱신\n',
        wp.category_label,
        wp.focus_label,
        wp.artifact_label,
        wp.maturity_label,
        wp.priority_label,
        wp.keyword_a,
        wp.keyword_b,
        wp.keyword_c,
        wp.category_label,
        wp.stage_label,
        wp.focus_label,
        wp.priority_label,
        wp.maturity_label,
        upper(substr(md5(wp.document_id), 1, 6)),
        ((wp.seq_no % 4) + 1),
        (50 + ((wp.seq_no * 11) % 50)),
        CASE WHEN (wp.seq_no % 3 = 0) THEN '예' ELSE '아니오' END,
        wp.keyword_a,
        wp.keyword_b,
        wp.keyword_c
    ) AS body_markdown,
    CASE
        WHEN (wp.ordinal_in_category % 15 = 0 AND wp.prev_document_id IS NOT NULL) THEN wp.prev_document_id
        ELSE wp.root_document_id
    END AS parent_document_id
FROM with_parent wp;

DROP TABLE IF EXISTS tmp_bulk_all_docs;
CREATE TEMP TABLE tmp_bulk_all_docs ON COMMIT DROP AS
SELECT
    r.document_id AS id,
    r.category_key,
    0 AS seq_no,
    0 AS ordinal_in_category,
    r.title,
    r.body_markdown,
    NULL::VARCHAR(36) AS parent_document_id
FROM tmp_bulk_roots r
UNION ALL
SELECT
    c.document_id AS id,
    c.category_key,
    c.seq_no,
    c.ordinal_in_category,
    c.title,
    c.body_markdown,
    c.parent_document_id
FROM tmp_bulk_children c;

INSERT INTO documents(
    id,
    workspace_id,
    title,
    body_markdown,
    body_text,
    blocks_json,
    parent_document_id,
    source_type,
    status,
    version,
    deleted,
    created_by,
    updated_by,
    created_at,
    updated_at
)
SELECT
    d.id,
    :'seed_workspace_id',
    d.title,
    d.body_markdown,
    d.body_markdown,
    NULL,
    d.parent_document_id,
    'EDITOR',
    'PROCESSING',
    0,
    FALSE,
    o.owner_id,
    o.owner_id,
    NOW(),
    NOW()
FROM tmp_bulk_all_docs d
CROSS JOIN tmp_bulk_owner o
ON CONFLICT (id) DO UPDATE
SET
    workspace_id = EXCLUDED.workspace_id,
    title = EXCLUDED.title,
    body_markdown = EXCLUDED.body_markdown,
    body_text = EXCLUDED.body_text,
    blocks_json = EXCLUDED.blocks_json,
    parent_document_id = EXCLUDED.parent_document_id,
    source_type = EXCLUDED.source_type,
    status = 'PROCESSING',
    deleted = FALSE,
    updated_by = EXCLUDED.updated_by,
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
    :'seed_workspace_id',
    d.id,
    'PENDING',
    'PENDING',
    'PENDING',
    'PENDING',
    NULL,
    NOW()
FROM tmp_bulk_all_docs d
ON CONFLICT (workspace_id, document_id) DO UPDATE
SET
    ingest_status = EXCLUDED.ingest_status,
    embed_status = EXCLUDED.embed_status,
    index_status = EXCLUDED.index_status,
    tree_status = EXCLUDED.tree_status,
    failure_reason = NULL,
    updated_at = EXCLUDED.updated_at;

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
    pg_temp.seed_uuid('bulk-docsaved:' || :'seed_workspace_id' || ':' || d.id),
    :'seed_workspace_id',
    d.id,
    'DocumentSaved',
    json_build_object(
        'document_id', d.id,
        'source_type', 'EDITOR',
        'seed', 'bulk-dataset'
    )::TEXT,
    'PENDING',
    0,
    NOW(),
    NOW(),
    NOW()
FROM tmp_bulk_all_docs d
ON CONFLICT (id) DO UPDATE
SET
    workspace_id = EXCLUDED.workspace_id,
    document_id = EXCLUDED.document_id,
    event_type = EXCLUDED.event_type,
    payload_json = EXCLUDED.payload_json,
    status = 'PENDING',
    retry_count = 0,
    available_at = NOW(),
    updated_at = NOW();

DROP TABLE IF EXISTS tmp_bulk_attachments;
CREATE TEMP TABLE tmp_bulk_attachments ON COMMIT DROP AS
WITH ratio AS (
    SELECT GREATEST(0, LEAST(100, (:'seed_attachment_ratio')::INT)) AS pct
)
SELECT
    pg_temp.seed_uuid('bulk-attachment:' || :'seed_workspace_id' || ':' || c.document_id) AS attachment_id,
    c.document_id,
    format('%s_%s_%s_%s.%s', c.category_key, c.keyword_a, c.maturity_code, LPAD(c.ordinal_in_category::TEXT, 4, '0'), c.file_ext) AS filename,
    c.content_type,
    (4096 + ((c.seq_no * 7919) % 1048576))::BIGINT AS size,
    format('workspaces/%s/attachments/%s/%s_%s_%s.%s', :'seed_workspace_id', c.document_id, c.category_key, c.keyword_b, LPAD(c.seq_no::TEXT, 5, '0'), c.file_ext) AS object_key,
    md5('bulk-attachment-checksum:' || c.document_id) AS checksum_sha256
FROM tmp_bulk_children c
CROSS JOIN ratio r
WHERE (c.seq_no % 100) < r.pct;

INSERT INTO attachments(
    id,
    workspace_id,
    document_id,
    filename,
    content_type,
    size,
    object_key,
    checksum_sha256,
    status,
    created_at,
    completed_at
)
SELECT
    a.attachment_id,
    :'seed_workspace_id',
    a.document_id,
    a.filename,
    a.content_type,
    a.size,
    a.object_key,
    a.checksum_sha256,
    'UPLOADED',
    NOW(),
    NOW()
FROM tmp_bulk_attachments a
ON CONFLICT (id) DO UPDATE
SET
    filename = EXCLUDED.filename,
    content_type = EXCLUDED.content_type,
    size = EXCLUDED.size,
    object_key = EXCLUDED.object_key,
    checksum_sha256 = EXCLUDED.checksum_sha256,
    status = 'UPLOADED',
    completed_at = NOW();

SELECT
    :'seed_workspace_id' AS workspace_id,
    (SELECT COUNT(*) FROM tmp_bulk_roots) AS seeded_root_docs,
    (SELECT COUNT(*) FROM tmp_bulk_children) AS seeded_child_docs,
    (SELECT COUNT(*) FROM tmp_bulk_all_docs) AS seeded_total_docs,
    (SELECT COUNT(*) FROM tmp_bulk_attachments) AS seeded_attachments,
    (SELECT COUNT(*) FROM documents d WHERE d.workspace_id = :'seed_workspace_id' AND d.deleted = FALSE) AS workspace_total_docs,
    (SELECT COUNT(*) FROM attachments a WHERE a.workspace_id = :'seed_workspace_id') AS workspace_total_attachments,
    (SELECT COUNT(*) FROM outbox_event oe WHERE oe.workspace_id = :'seed_workspace_id' AND oe.event_type = 'DocumentSaved' AND oe.status IN ('PENDING', 'RETRY', 'PROCESSING')) AS pending_docsaved_events;

COMMIT;
