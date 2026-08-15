# GateLink @VERSION@ - Docker Compose bundle

This archive is published as:

```text
quarkus-gatelink-webpush-compose-@VERSION@.zip
quarkus-gatelink-webpush-compose-@VERSION@.tar.gz
```

The archive root directory is `quarkus-gatelink-webpush-compose-@VERSION@` and contains the prebuilt Quarkus server and Angular UI. Maven and Node.js are not required on the target host.

## Runtime contract

```text
Browser/operator
      |
      | HTTPS 443 (normal user entry)
      v
quarkus-gatelink-webpush-ui
      |
      | HTTPS 8443, Docker DNS
      v
quarkus-gatelink-webpush-server
      |
      v
postgres
```

Fixed service/container/hostname values are `quarkus-gatelink-webpush-ui` and `quarkus-gatelink-webpush-server`.

Published ports:

- UI HTTP `80` redirects to HTTPS;
- UI HTTPS `443` is the normal user entry point;
- Quarkus HTTP `8080` remains available for direct REST/operations access;
- Quarkus HTTPS `8443` remains available for direct REST/operations access.

## Start

```bash
cp .env.example .env
# edit secrets and TLS SAN values if the host is not localhost
docker compose up -d --build --wait
```

Self-signed certificates are generated automatically on first start and persisted in Docker volumes. Browsers/clients must explicitly trust or accept them.

Verify:

```bash
curl -k https://127.0.0.1/healthz
curl -k https://127.0.0.1/api/q/health/ready
curl http://127.0.0.1:8080/q/health/ready
curl -k https://127.0.0.1:8443/q/health/ready
docker compose ps
```

Nginx proxies `/api/` to `https://quarkus-gatelink-webpush-server:8443/` on the private Compose network.

`docker compose down` preserves PostgreSQL data and generated TLS material. Do not use `docker compose down -v` unless all named-volume data should be deleted.
