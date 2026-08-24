# Server-side analytics with Matomo

Browser-side analytics is unreliable for a technical audience: most desktop visitors run a content blocker, so
a JavaScript tracker never even loads. This server reports usage to [Matomo](https://matomo.org) from the
backend instead, through Matomo's
[HTTP Tracking API](https://developer.matomo.org/api-reference/tracking-api). There is nothing to load in the
browser, nothing to block, and no cookie.

## Setup with Docker (this deployment)

`docker-entrypoint.sh` runs the server against the tracked `config-example.yml`, so the auth token must **not**
be written there - it would end up in git. The entrypoint turns `MATOMO_*` environment variables into
Dropwizard `-Ddw.matomo.*` overrides instead. Copy `.env.example` to `.env` (gitignored), fill it in, and
restart:

```bash
cp .env.example .env
$EDITOR .env          # MATOMO_ENABLED=true, url, site_id, token, site_url, salt
docker compose up -d --force-recreate
```

The startup log prints `Matomo tracking enabled -> <url>` followed by
`Matomo server-side tracking enabled, endpoint=... idsite=...` once the tracker is up.

## Setup without Docker

Add an auth token in Matomo under *Administration > Personal > Security > Auth tokens*, then fill in the
`matomo` block of the config file you pass to the `server` command:

```yaml
matomo:
  enabled: true
  url: https://analytics.example.com/matomo.php
  site_id: 1
  token_auth: "<your token>"
  site_url: https://maps.example.com
  visitor_id_salt: "<any random string>"
  # behind nginx / Caddy / Traefik, otherwise every visitor looks like the proxy
  trust_forwarded_for: true
```

The token is mandatory. Without it Matomo refuses the `cip`, `ua` and `cdt` overrides, and since the hits come
from the server every visitor would be attributed to the server's own IP and user agent.

Any of these keys can also be overridden on the command line, which is what the Docker entrypoint does:

```
java -Ddw.matomo.enabled=true -Ddw.matomo.token_auth=... -jar graphhopper-web.jar server config.yml
```

Note that this config file is *not* run through variable substitution, so `${MATOMO_TOKEN}` in the YAML would
be taken literally. Use the `-Ddw.` overrides for secrets.

In Matomo, make sure the site's *Excluded IPs* list does not contain the server itself, and leave bot exclusion
on: the real user agent is forwarded, so Matomo filters crawlers the same way it does for JavaScript tracking.

## What gets reported

| Request | Reported as |
| --- | --- |
| `/`, `/maps/`, `/maps/isochrone/`, `/maps/pt/`, `/maps/map-matching/` | page view, named `Maps`, `Maps / Isochrone`, ... |
| `/route`, `/route-pt`, `/isochrone`, `/spt`, `/nearest`, `/match`, `/navigate` | event, category `API`, action = endpoint, name = profile, value = response time in ms |
| the same endpoints answering 4xx/5xx | event, category `API error`, name = `HTTP 400` etc. |
| static assets, vector tiles (`/mvt`), `/info`, `/i18n`, health checks | nothing |

Because the map UI is a single-page app, a visit is one page view plus one event per search. Response time
lands in the event value, so Matomo's *Behaviour > Events* report doubles as a latency-per-profile view.

Redirects are skipped: `GET /` answers `303` towards `/maps/`, and the browser then requests the real page, so
counting both would double every visit.

## Privacy

* No cookie, no browser storage, no JavaScript.
* Visitors are identified by `sha256(salt + IP + user agent + UTC day)`, truncated to the 16 hex characters
  Matomo expects. The identifier rotates daily and cannot be reversed into an IP.
* `anonymize_ip` (on by default) drops the last octet of IPv4 addresses and the last 80 bits of IPv6 ones
  before the IP is sent to Matomo.
* Only the query parameters in `tracked_query_params` end up in the reported URL. Coordinates (`point`,
  `point_hint`) are never sent, so route origins and destinations stay on this server.
* A `POST /route` carries its profile in the JSON body. The request body is deliberately never read, so those
  events are reported as `unknown profile`.

## Accuracy

Server-side counts of page views and searches are exact - that is the point of doing it here. Visitor counts
are approximations: visitors sharing an IP and a user agent (a company NAT, a mobile carrier's CGNAT) collapse
into one, and a visitor whose IP changes mid-day counts twice. Aggregate trends are reliable; treat "unique
visitors" as a lower bound.

## Operations

Tracking runs on a single background thread with a bounded queue:

* `track` never blocks. Once the queue is full, hits are dropped and counted rather than slowing down routing.
* Hits are sent in Matomo bulk requests, up to `batch_size` per call, flushed after `flush_interval_ms`.
* If Matomo is unreachable or answers an error, the batch is dropped and a warning is logged at most once per
  minute. Routing is unaffected.
* Totals for hits sent, dropped and failed are logged on shutdown.

Set `api_sample_rate` below `1.0` to report only a fraction of the API events on a busy instance. Page views
are never sampled, so visit counts stay intact.
