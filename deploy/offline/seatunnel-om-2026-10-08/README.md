# OpenMetadata 2.0.4 + SeaTunnel 3.0.0 offline package

This bundle is prepared for `linux/arm64`, matching the current SeaTunnel 3.0.0 deployment host. It contains pinned release images, the 3.0.0 connector and driver directories used by the compatibility stack, a Compose definition, and this deployment guide. No local database volume, `.env`, datasource credential, or task configuration is included.

The OpenMetadata services follow the official 2.0.4 quickstart image set: database `docker.getcollate.io/openmetadata/db:2.0.4`, server `docker.getcollate.io/openmetadata/server:2.0.4`, and Elasticsearch `docker.elastic.co/elasticsearch/elasticsearch:9.3.0`. The default ingestion image is the local custom `2.0.4` extension image used for Kingbase, Dameng, and Vastbase. The image archive also includes the official `docker.getcollate.io/openmetadata/ingestion:2.0.4`; set `OPENMETADATA_INGESTION_IMAGE` to that tag only if the custom adapters are not needed. See the official [OpenMetadata 2.0.4 Compose file](https://raw.githubusercontent.com/open-metadata/OpenMetadata/2.0.4-release/docker/docker-compose-quickstart/docker-compose.yml).

SeaTunnel runs a Zeta master and two workers from `apache/seatunnel:3.0.0`. The image itself does not contain every production connector, so the Compose file mounts `seatunnel/connectors` and `seatunnel/lib`. These directories are exported from the verified local 3.0.0 stack; they include the file, JDBC, CDC, S3, Doris, Elasticsearch, Kafka and HTTP connector jars plus the driver jars needed by its test matrix. The [SeaTunnel Docker guide](https://seatunnel.apache.org/docs/getting-started/docker/) documents the cluster entrypoint and connector installation requirement.

## Files

- `compose.yml`: OpenMetadata 2.0.4, its database/search/ingestion dependencies, and a three-node SeaTunnel 3.0.0 cluster.
- `.env.example`: placeholders only. Copy it to `.env` and replace every `replace-*` value before deployment.
- `images-arm64.tar`: image archive created by `docker save`.
- `seatunnel-plugins-arm64.tar.gz`: `connectors/` and `lib/` runtime dependencies. Extract this into the package directory so the resulting paths are `./seatunnel/connectors` and `./seatunnel/lib`.
- `SHA256SUMS`: checksums generated after export.

## Offline deployment

1. Copy the package directory to the target Docker host and verify its checksums:

   ```sh
   sha256sum -c SHA256SUMS
   ```

2. Load the image archive and unpack the connector libraries:

   ```sh
   docker load --input images-arm64.tar
   mkdir -p seatunnel
   tar -xzf seatunnel-plugins-arm64.tar.gz -C seatunnel
   ```

3. Create the deployment environment file and set unique credentials:

   ```sh
   cp .env.example .env
   chmod 600 .env
   ```

   Generate a Fernet key on a trusted host, then set `OM_AIRFLOW_FERNET_KEY`:

   ```sh
   python3 -c 'import base64,os; print(base64.urlsafe_b64encode(os.urandom(32)).decode())'
   ```

   Keep the populated `.env` private.

4. Review host ports in `.env`. Defaults are OpenMetadata `18585`/`18586`, SeaTunnel Hazelcast `5802`, and SeaTunnel REST `8083`.

5. Start the package on the target host:

   ```sh
   docker compose --env-file .env -f compose.yml up -d
   docker compose --env-file .env -f compose.yml ps
   ```

6. Check the services before connecting SeaTunnel Web:

   ```sh
   curl -fsS http://127.0.0.1:18586/healthcheck
   curl -fsS http://127.0.0.1:8083/overview
   ```

OpenMetadata is at `http://<host>:18585`. Configure SeaTunnel Web's client against the target host's mapped Engine port `5802` and set its engine version to `3.0.0`. Configure the OpenMetadata adapter against `http://<host>:18585`; keep the password in the Web server's secret store or protected environment.

## Notes

- The package is ARM64. An AMD64 target needs matching AMD64 image archives and a connector/runtime bundle from an AMD64 SeaTunnel 3.0.0 installation.
- Use a new or backed-up OpenMetadata database volume. The migration service applies the 2.0.4 schema before the server starts.
- The ingestion image tag in the default environment is a custom local image. It must exist in the loaded archive. If that custom image is absent, set `OPENMETADATA_INGESTION_IMAGE=docker.getcollate.io/openmetadata/ingestion:2.0.4` to use the stock image included in the archive; Kingbase, Dameng, and Vastbase ingestion adapters will then be unavailable.
- Do not expose MySQL, Elasticsearch, or the ingestion API directly to the intranet. Compose keeps them on its private bridge network.
- SeaTunnel connectors and JDBC drivers may have separate license terms. Keep the bundle within the organization's authorized use.
