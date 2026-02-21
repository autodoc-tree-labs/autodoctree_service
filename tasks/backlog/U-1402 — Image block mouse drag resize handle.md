# U-1402 — Image block mouse drag resize handle

## Context
- EditorV2 supports image upload and width preset select, but does not support direct mouse drag resize.
- Users expect block-image resizing directly on canvas.

## Goal
- Enable mouse drag resize for image blocks and persist width in `blocks_json`.

## Non-goals
- Image crop/rotate.
- Multi-select resize.

## Scope
- Add image node resize handle (right edge) in EditorV2.
- While dragging, update visual width and commit `width` attribute on pointer release.
- Keep current width preset select working.

## API / Contracts
- No API changes.

## DB / Index changes
- None. Uses existing `blocks_json` image `width` attr.

## Happy path
1. User selects image block.
2. User drags resize handle left/right.
3. Image width updates and saves in block attrs.
4. Reload document preserves resized width.

## Edge cases
- Clamp width to safe range (min/max).
- Ignore drag when editor is read-only.

## Acceptance Criteria
- [ ] Mouse drag resize works on image blocks.
- [ ] Width persists after save/reload.
- [ ] Existing toolbar width presets still apply.

## Testing
- Build/lint pass.
- Existing web-user e2e regression pass.

## Observability
- No additional logs/metrics.

## Rollout / Rollback
- Ships within existing EditorV2 rollout.

## Security / Privacy
- No tenant/auth changes.
