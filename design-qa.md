# UI redesign visual QA

## Scope

- Reference HTML: `tmp/北京项目-切图0827/index.html`
- Reference HTML: `tmp/北京项目0904/index.html`
- Relevant reference artboards: `数据源管理`, `数据源管理备份`, `数据源管理备份 2`, `数据源管理备份 7`, `离线引接任务`
- Primary viewport: `1920 × 1080`
- The two reference HTML files have the same geometry on the data-source and offline-task artboards.

## Implemented areas

- Matched the 80px header, fixed left navigation, page title bars, filter panels, action buttons, and dark cyan visual tokens.
- Rebuilt the data-source cards into the three-column layout with lifecycle/profile states, metadata, ribbon, and visible action bar.
- Rebuilt the data-source type selector, the two-step editor, dynamic connection fields, driver upload row, connection key/value row, and the unit/business-system drawer.
- Reworked the offline-task filters, fixed table layout, two-row source/sink plan, health status badge, execution/schedule information, and operation column.
- Preserved the existing API calls and state transitions; visual changes do not write fixture data to the repository or database.

## Geometry checks

| Area | Reference | Rendered |
| --- | ---: | ---: |
| Data-source title bar | `x=227, y=99, w=1673, h=38` | matched |
| Data-source filter panel | `x=227, y=157, w=1673, h=130` | matched |
| Data-source cards | `x=227/794/1361, y=339.34, w=537, h=258` | matched |
| Offline-task table shell | `x=227, y=336.5, w=1673, h=399` | matched |
| Offline-task rows | `y=374.5/555.5, h=181` | matched |
| Editor modal content | `x=255, y=111, w=1196, h=998` | matched |
| Editor modal footer | `y=1049, h=60` | matched |
| Health status badge | `x=963, y=457, w=57, h=28` | matched |

## Evidence and interaction checks

The rendered states were captured at the primary viewport with headless Chrome over the local dev server:

- `/tmp/seatunnel-data-source-cards-fixture.png`
- `/tmp/seatunnel-data-source-type-fixture.png`
- `/tmp/seatunnel-data-source-form-fixture.png`
- `/tmp/seatunnel-data-source-master-drawer-fixture.png`
- `/tmp/seatunnel-batch-table-fixture.png`
- `/tmp/seatunnel-data-source-empty-final-3.png`
- `/tmp/seatunnel-batch-empty-final-3.png`

Checked states include opening the new-data-source flow, selecting a suggested connector, advancing to the information step, closing the editor, opening the maintenance drawer, and rendering both populated and empty list states. The local API was unavailable for the populated visual state, so a temporary Fetch interception supplied representative read-only responses; it was kept under `/tmp` and made no persistent writes. Playwright MCP was unavailable because its browser was already in use; the fallback browser capture used the same fixed viewport and direct CDP screenshots.

## Verification

- `npm run tsc` — passed
- `git diff --check` — passed

final result: passed
