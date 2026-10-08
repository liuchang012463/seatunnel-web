# OpenMetadata 2.0.4 / SeaTunnel 3.0.0 review

Review date: 2026-10-08  
Branch under review: `codex/openmetadata-2.0.4-upgrade`, based on local `develop`  
Scope: OpenMetadata 2.0.4 integration, SeaTunnel Engine 3.0.0 compatibility, all registered datasource types, and desktop web UX.

## Evidence and review limits

- Review-only screenshots were captured for the datasource list, OpenMetadata status, file-transfer list, batch list, and the original existing-job edit failure. They contain internal host addresses, datasource identifiers, or task IDs, so they are intentionally excluded from the committed report.
- The existing 2026-10-07 compatibility report records Engine 3.0.0 runs for batch, realtime, file ingest, and file transfer. Those are inherited test records, not reruns from this review.
- The current local OpenMetadata status screen reports Server `2.0.4` and ingestion `2.0.4.0` as compatible.
- The interactive browser tools rejected click and form actions with `approval policy is never`. This review therefore does not claim new browser happy-path acceptance.
- No Compose stack was started, stopped, or restarted for this review.

## Data-source coverage inventory

| Web datasource | SeaTunnel task path recorded in project | OpenMetadata 2.0.4 adapter | Review status |
| --- | --- | --- | --- |
| JDBC | JDBC batch/source/sink | Historical JDBC rows; only verified PostgreSQL/MySQL schemes are accepted | Static review; other driver schemes are intentionally rejected |
| MySQL | JDBC batch/source/sink, MySQL CDC | MySQL DatabaseService | Prior Engine 3.0.0 matrix covers batch, CDC, and sinks |
| PostgreSQL | JDBC batch/source/sink, PostgreSQL CDC | Postgres DatabaseService | Batch path passed; PostgreSQL CDC snapshot/INSERT passed, UPDATE propagation and stop path remain unresolved (COMPAT-04) |
| Oracle | JDBC batch/source/sink | Oracle DatabaseService | Prior Engine 3.0.0 matrix covers source and sink |
| Dameng | JDBC batch/source/sink | CustomDatabase extension | Prior Engine 3.0.0 source path passed; MySQL→Dameng sink is currently FAILED on case-sensitive column names (COMPAT-03) |
| Vastbase | JDBC batch/source/sink | CustomDatabase extension | Prior Engine 3.0.0 matrix covers source and sink; driver collision fix recorded |
| Kingbase | JDBC batch/source/sink | CustomDatabase extension | Prior Engine 3.0.0 matrix covers source and sink |
| Doris | Doris batch/source/sink | Doris DatabaseService | Prior Engine 3.0.0 matrix covers source and sink |
| Elasticsearch | Elasticsearch batch and realtime source; sink is exposed in the UI registry | Elasticsearch search service | Prior Engine 3.0.0 matrix covers batch and realtime source |
| Kafka | Kafka source/sink and realtime source | Kafka messaging service | Prior Engine 3.0.0 matrix covers batch and realtime source |
| HTTP | HTTP source, including realtime polling | REST service; requires `openApiSpecUrl` for metadata extraction | Prior Engine 3.0.0 matrix covers batch source |
| FTP | File-transfer source/sink | No adapter registered | Engine file-transfer compatibility is separate; OM sync is unsupported |
| SFTP | File-transfer source/sink | SFTP Drive service | Prior Engine 3.0.0 matrix covers file transfer |
| Amazon S3 | S3File source/sink | S3 StorageService | Prior Engine 3.0.0 matrix covers file transfer |
| MinIO | S3File source/sink | S3-compatible StorageService | Prior Engine 3.0.0 matrix covers file transfer and file ingest targets |
| ZeoneDB-D | JDBC source/sink | PostgreSQL-compatible DatabaseService | No usable instance/driver was available in the inherited test record |
| H2 | Internal/test datasource; not creatable in the datasource UI | No adapter registered | Not a user-configurable production datasource |
| FILE_RESOURCE / web upload | File ingest and file-transfer source | Managed file source, not a registered datasource service | Prior Engine 3.0.0 record covers file ingest and file transfer |

## Findings recorded before applicable fixes

| ID | Priority | Surface | Finding | Evidence / disposition before fix |
| --- | --- | --- | --- | --- |
| UX-01 | P2 | Datasource list | Datasource labels are clipped in the card list, so users cannot reliably distinguish similarly named sources. | Review-only local screenshot and current UI inspection. |
| UX-02 | P2 | Batch/realtime/file task lists | The sync-plan cell gives too little room to the source/target labels and truncates long datasource names, making similarly prefixed endpoints hard to distinguish. | Review-only local screenshot; shared task-list component and 198 px plan column. |
| UX-03 | P2 | Batch task edit | An existing task edit route ends in a blank `Empty` state saying configuration was not found. The page only exposes the more useful online-task restriction in a transient toast. | Review-only local screenshot; live route and current frontend code. |
| DATA-01 | P1 | Batch/realtime edit routing | When `scene` is absent, a same-ID `sessionStorage` creation draft takes precedence over the server edit detail. A stale draft can therefore open an existing task as a new task. | Static route analysis; all in-repository create links explicitly use `scene=create`, so missing-scene routes can safely default to edit. |
| DOC-01 | P2 | Project version constraints | The 2.3.13 baseline was easy to mistake for the only supported Engine version, and file-sync design documents did not reflect the version-aware 3.0.0 behavior. | Current docs and `SeaTunnelClientVersionPolicy`; readers need an explicit dual-version boundary. |
| DOC-02 | P2 | Compatibility report | The 2026-10-07 report retains earlier “待修复” entries alongside later fixes and reviewer corrections. | Existing historical audit; this dated issue log supplies the current disposition without erasing its evidence. |
| COVERAGE-01 | P2 | ZeoneDB-D | No real Engine 3.0.0 end-to-end test was possible without an instance and matching driver. | Inherited 2026-10-07 test record; leave explicitly unverified. |
| COVERAGE-02 | P2 | OpenMetadata FTP | FTP has no `MetadataConnectorAdapter`; it must remain visibly unsupported in OM sync rather than be represented as SFTP. | Adapter registry review; task-engine FTP support remains separately tested. |
| COVERAGE-03 | P3 | H2 | H2 is in the backend enum but is marked non-creatable in the UI and has no OM adapter. | Registry review; internal/test type, outside user-configurable production-source acceptance. |
| COMPAT-03 | P1 | MySQL→Dameng | Sink fails with `Invalid column name [id]`: SeaTunnel emits quoted lowercase source columns while the existing Dameng table has unquoted uppercase columns. | User-updated 2026-10-08 compatibility matrix. The environment user lacks CREATE TABLE permission, so the alternative lowercase quoted target schema cannot be verified. The current evidence points to Engine dialect / target-schema behavior; do not claim fixed in this Web repository. |
| COMPAT-04 | P1 | PostgreSQL CDC→MySQL | Snapshot and INSERT land, but UPDATE does not reach the target; the task later ends FAILED after worker-to-master metrics heartbeats time out and the CDC source throws `NullPointerException`. | User-updated 2026-10-08 compatibility matrix. The failure is primarily in the Engine 3.0 runtime/cluster. The task's sink write mode and primary-key config must also be checked before attributing UPDATE loss to the Engine. |
| UX-14 | P2 | File-sync publish checklist | After the UI and backend began rejecting FTP↔SFTP incremental sync, the pre-publish checklist still failed to identify stale or hand-edited cross-protocol configurations. | Luna Max second-pass review. The publish action could reach the server and fail late; the checklist now reports the protocol mismatch before publish. |

## Other reviewed boundaries

- The OpenMetadata registry maps all user-facing datasource categories except FTP; H2 is internal and non-creatable. Non-relational adapters use OpenMetadata service types rather than DatabaseService.
- Data exploration/topology APIs are intentionally limited to relational/database types. Kafka, Elasticsearch, HTTP, SFTP, S3, and MinIO do not expose relational table topology through those APIs.
- The prior 3.0.0 matrix records a Vastbase PostgreSQL-driver collision fix and passing PostgreSQL/Vastbase reads after the conflicting jar was removed from the Engine runtime. This review does not re-inspect or mutate that runtime.
- The working copy's latest 2026-10-08 correction supersedes older “pass” entries for MySQL→Dameng and PostgreSQL CDC UPDATE/stop behavior; both remain open pending Engine or test-environment evidence.
- The prior report marks ZeoneDB-D and SFTP path-root semantics as unverified boundaries. They are not counted as full compatibility passes.

## Fixes and current dispositions

| ID | Disposition | Change / evidence |
| --- | --- | --- |
| UX-01 | Fixed | Datasource labels now wrap to two lines and clamp, so long names remain distinguishable in the card list. |
| UX-02 | Fixed | Batch, realtime, and file task rows show source/target datasource names with task-type labels. The plan column is widened to 260 px and names wrap to display their full value without relying on hover. |
| UX-03 | Fixed in code | Editing an online task now shows the offline-only restriction with a Back action instead of an empty “configuration not found” state. Browser click-through remains unverified because the current browser tool denies interactions. |
| DATA-01 | Fixed | A config URL without `scene` now uses edit mode and loads the saved server definition; a stale creation cache cannot take precedence. Explicit create routes retain `scene=create`. |
| HTTP-01 | Fixed | Shared batch/realtime validation rejects HTTP schema fields with missing types before save/publish. The earlier claim that realtime lacked this rule was a separate false positive and remains withdrawn. |
| FILE-01 | Fixed | File-sync source HOCON receives the sink path; incremental capability requires the same datasource and protocol (FTP→FTP, SFTP→SFTP, S3→S3, or MinIO→MinIO). S3/MinIO additionally requires Engine 3.0.0. Validation rejects unsupported formats and `checksum` without `strict`. |
| AUDIT-02 | Fixed | Unexpected HOCON build failures return the stable generic build error; logs include only mode and failure class, never exception text or stack trace. A synthetic `pwd=...` failure test verifies the API and log output. |
| DOC-01 | Fixed | The datasource guide now distinguishes the general 2.3.13 baseline from version-gated 3.0.0 support. FTP/SFTP and S3/MinIO design docs describe actual version differences and known limits. |
| DOC-02 | Reconciled | This table is the current disposition for the earlier report. Historical rows are retained as evidence and are superseded where this review marks an item fixed or still open. |
| COVERAGE-01/02/03 | Open, bounded | ZeoneDB-D lacks a usable Engine/driver test instance; FTP has no OM adapter; H2 is internal and non-creatable. These are not reported as passing user-configurable OM integrations. |
| COMPAT-03/04 | Open, external | Dameng identifier case behavior and PG-CDC UPDATE/stop failures remain outside a safe Web-only fix; the updated compatibility matrix records their actual failed outcomes. |
| UX-A12 | Open | Recovering an unsaved file-sync draft across reload still needs server-side draft persistence; persisting connection-bearing configuration in browser storage is unsafe. |
| UX-A13 | Fixed in code, browser check pending | File-sync validation blocks publish when errors exist and the publish path surfaces save failures; the checklist also catches cross-protocol incremental configs. Interaction could not be exercised in this browser session. |
| UX-14 | Fixed in second review iteration | The publish checklist now rejects FTP→SFTP and SFTP→FTP incremental configurations even when the datasource ID matches; tests cover both directions. |
| SFTP key authentication | Known limitation | SeaTunnel 3.0.0 supports a `keyfile`, but the Web datasource form still exposes password authentication only. This remains outside the compatibility patch. |

## Luna Max review iterations

- Review 1: **FAIL**. Found exception-message leakage and FTP↔SFTP incremental configurations that could pass when datasource IDs matched. Both were fixed in the backend, and the UI capability predicate and regression tests were updated.
- Review 2: **FAIL**. Found that the file-sync publish checklist did not report the cross-protocol mismatch on stale or hand-edited workflows. Added a pre-publish error and bidirectional tests. The same review also found that long task-list datasource names were still truncated.
- Review 3: **PASS**. Widened the shared task-list sync-plan column from 198 px to 260 px and allowed endpoint names to wrap fully. Luna Max confirmed the prior fixes and offline package had no regressions.
- The reviewer did not claim browser acceptance: the current Chrome/Playwright session still rejects interaction actions with `approval policy is never`. No Compose deployment was run.

## User-view flow and accessibility notes

1. **Choose a datasource** — the local review screenshot showed type filters and connection-status chips together. Long connection summaries were still ellipsized, while the datasource name remains the primary identifier.
2. **Check OpenMetadata connectivity** — the local review screenshot showed Server, ingestion, orchestrator, and fixed-version compatibility in one status view; the token was not echoed.
3. **Review file-transfer tasks** — the local review screenshot showed task state, source/target connector types, run state, schedule, and actions. Rows are information-dense.
4. **Review batch tasks** — the local review screenshot showed that status and run controls are easy to locate; source/target labels and secondary values were too truncated before the row-layout fix.
5. **Edit an online task** — the local screenshot records the original misleading empty state. The live post-fix page snapshot now displays “任务已上线，请先下线任务再编辑配置” and a “返回任务列表” action. A fresh screenshot could not be saved because the browser tool rejected its output path.

Small table text and dense rows are visible accessibility risks in screenshots. Keyboard focus order, screen-reader names for icon actions, zoom reflow, and measured color contrast still need browser or assistive-technology checks; screenshots alone do not establish WCAG conformance.

## Verification and delivery status

- Frontend targeted Jest: the earlier 4 suites/33 tests passed; the latest file-sync checklist and protocol matrix pass (2 suites/20 tests). `npm run tsc` passed after the final UI/style changes.
- Maven clean reactor for core, FTP, and S3: 16 earlier focused tests passed (version policy 4, sanitized error 1, FTP builder 3, S3 builder 8). The latest focused core reactor passes 7 tests, including generic error redaction and cross-protocol FILE_SYNC rejection. Main API reactor compile with test compilation skipped passed.
- API test compilation is blocked by unrelated existing test-source errors in the API module (`LakePhysicalDataSourcePageDTO` setters and a `LakeProjectionSaveWiringTest` type mismatch). The new client-version service test could not be executed in that module; the production API code compiles.
- Compose YAML syntax parses successfully; all required substitutions are represented in `.env.example`. No Compose command or container lifecycle action was run.
- Browser screenshots and the current page snapshot support the visual findings. Chrome `click` was rejected with `approval policy is never`, even after switching to the interactive session; no new UI happy path is claimed as accepted.
- The offline Compose and deployment guide are staged in `deploy/offline/seatunnel-om-2026-10-08/` and copied to `/mnt/lc/tmp/seatunnel-om-offline-20261008/`. Its image archive contains the six Compose images (OM 2.0.4 DB/server/official ingestion, custom 2.0.4 ingestion, Elasticsearch 9.3.0, and SeaTunnel 3.0.0), each verified as `linux/arm64`; the SeaTunnel connector/runtime archive includes `connectors/` and `lib/`. `SHA256SUMS` was generated and all six package files verified. The package has placeholders only and contains no `.env`, datasource credentials, database volumes, or task configuration.
