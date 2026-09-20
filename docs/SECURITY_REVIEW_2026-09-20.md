# Public exposure hardening — 2026-09-20

Scope: targeted source review and non-destructive production checks, not a complete penetration test.

## Changes

- Resolve forwarded client addresses from right to left, stopping at the first untrusted proxy. Reject malformed addresses without DNS lookups. Regression tests cover forged prefixes, IPv6 and malformed hops.
- Include `/local-workspace/step/stream` in the existing chat rate limit. A regression test verifies its shared budget and spoofed-IP resistance.
- Production frontend configuration in the frontend repository (`deploy/nginx.production.conf`) blocks public `/api/internal` and `/api/actuator` routes and dotfiles, suppresses the Nginx version, and adds browser security headers. The CSP restricts objects, base URLs and embedding; it is not a full script allowlist.
- `scripts/qdrant-ingress-guard.sh` denies incoming TCP 6333/6334 on the external interface in INPUT and Docker's DOCKER-USER chains, for IPv4 and IPv6 where the chain exists. Internal Docker traffic and host loopback remain available. Its systemd unit restores rules after boot/Docker restart. Install the script at `/usr/local/sbin/lyle-qdrant-ingress` and the unit under `/etc/systemd/system/`.

## Evidence and limits

- Before hardening, the public Qdrant collections endpoint returned 401 without an API key. Authentication was already enabled; anonymous data access was not established. The unnecessary public network exposure remains a defense-in-depth concern.
- Other shared-host services publish ports on all interfaces: MySQL 3307, Redis 6379, PostgreSQL 5432, Redis Commander 8083, RabbitMQ 5672/15672, Kafka 9092 and n8n 5678. Their consumers and outside-network requirements have not been established. Do not stop or reconfigure unrelated applications as part of this release. Review their ingress separately; all-interface binding alone does not prove data access or compromise.
- Existing access-token authentication, refresh-origin checks and internal service tokens were found. Sampled file endpoints pass the authenticated user ID to services. This is not proof that every authorization path is correct.
- No production user data was changed for verification, and no model generation or payment was initiated. Dependency-wide vulnerability scanning, exhaustive cross-account tests and historical compromise analysis remain outside this targeted pass.

## Rollback

Restore the preceding release's Compose files and image tags, verify health, then atomically restore `/opt/lyle/current`. Firewall rollback is independent: disable the unit, then run `lyle-qdrant-ingress remove eth0` only if restoring external database access is explicitly intended. Never flush host firewall chains.

References: https://docs.docker.com/engine/network/firewall-iptables/ and https://qdrant.tech/documentation/security/.
