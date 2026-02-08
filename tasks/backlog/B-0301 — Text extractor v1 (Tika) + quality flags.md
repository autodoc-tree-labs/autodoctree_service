# B-0301 — Text extractor v1 (Tika) + quality flags


## Goal
PDF/DOCX/TXT/MD에서 텍스트 추출.

## Scope
- Apache Tika 기반 extractor
- quality flags:
  - ZERO_LENGTH, TOO_SHORT, GIBBERISH
- encrypted PDF → FAILED(ENCRYPTED_PDF)
- scanned PDF → ZERO_LENGTH (OCR은 v1.1)

## Acceptance Criteria
- 추출 성공 시 body_text 또는 sections 저장
- 실패/품질낮음은 ingest stage에 기록

