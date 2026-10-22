# Changelog

All notable changes to SeaTunnel Web will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses semantic versioning where practical.

## [Unreleased]

### Added

- Initial project documentation and GitHub community health files.
- Issue templates for bug reports and feature requests.
- Pull request template for contributor checklists.
- CI and release workflow skeletons.
- OpenLineage lineage bridge: job lineage is published to the Kafka topic `meta_lineage_topic` as
  OpenLineage `RunEvent` JSON with the local dataset identity, one START event per accepted
  submission, periodic RUNNING heartbeats for streaming jobs and exactly one terminal event per run.
- Complex lineage support: multiple input and output datasets (the 1:1, N:1, 1:N and N:M table
  cardinalities), column lineage bound to the source table of every column, and cross datasource jobs
  whose plugin datasource is resolved by its connection.
- The lineage topic is created when the broker does not have it yet, following the cluster defaults
  for partitions and replication factor.

### Changed

- The direct OpenMetadata lineage write of the web application was removed: table and column lineage
  is now written by the asset project after it consumes the lineage topic, which is the only path
  that supports many to many lineage.

### Fixed

- Multi table jobs no longer publish a single dataset whose name is the raw table list; every table
  of the job is published as its own dataset.
- The lineage topic of a broker without automatic topic creation is no longer dropped silently.

## [1.0.0] - 2026-07-12

### Added

- SeaTunnel Web backend modules for API, core services, datasource connections, DAO plugins, SPI, engine client, and distribution packaging.
- SeaTunnel Web UI for visual pipeline creation, datasource management, task execution, monitoring, and logs.

[Unreleased]: https://github.com/weifuwan/seatunnel-web/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/weifuwan/seatunnel-web/releases/tag/v1.0.0
