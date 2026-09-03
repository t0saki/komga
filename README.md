[![Open Collective backers and sponsors](https://img.shields.io/opencollective/all/komga?label=OpenCollective%20Sponsors&color=success)](https://opencollective.com/komga) [![GitHub Sponsors](https://img.shields.io/github/sponsors/gotson?label=Github%20Sponsors&color=success)](https://github.com/sponsors/gotson)
[![Discord](https://img.shields.io/discord/678794935368941569?label=Discord&color=blue)](https://discord.gg/TdRpkDu)

[![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/gotson/komga/tests.yml?branch=master)](https://github.com/gotson/komga/actions?query=workflow%3ATests+branch%3Amaster)
[![GitHub release (latest SemVer)](https://img.shields.io/github/v/release/gotson/komga?color=blue&label=download&sort=semver)](https://github.com/gotson/komga/releases) [![GitHub all releases](https://img.shields.io/github/downloads/gotson/komga/total?color=blue&label=github%20downloads)](https://github.com/gotson/komga/releases)
[![Docker Pulls](https://img.shields.io/docker/pulls/gotson/komga)](https://hub.docker.com/r/gotson/komga)

[![Translation status](https://hosted.weblate.org/widgets/komga/-/webui/svg-badge.svg)](https://hosted.weblate.org/engage/komga/)

# ![app icon](./.github/readme-images/app-icon.png) Komga

Komga is a media server for your comics, mangas, BDs, magazines and eBooks.

#### Chat on [Discord](https://discord.gg/TdRpkDu)

## About this fork

This is a fork of [gotson/komga](https://github.com/gotson/komga). A daily job merges the newest
upstream **release** and rebuilds the image, so it stays on a stable version plus the changes below.

Docker image: `ghcr.io/t0saki/komga:latest`

### The problem it solves

Upstream's web reader asks the server for one image at a time
(`/api/v1/books/{id}/pages/{n}`). For every page, the server opens the CBZ, pulls out that
one image, and sends it back. A 200-page book is 200 round trips, each costing server work.
Over a slow or long-distance link, that is where the waiting comes from.

This fork lets the **browser** download the comic file and unzip pages itself. The server just
hands over a file — no per-page work at all.

### The one setting

**Read pages from the book file (memory limit)**, in your account settings. Default `500 MB`;
`Disabled` gives you upstream behaviour back. It applies to CBZ/ZIP comics and needs the
*File Download* permission.

One number, because the three things it controls are the same question asked three ways:

- **Whether to do this at all** — `Disabled` turns it off.
- **How much memory to spend** — decoded pages are held up to this much.
- **How the file is fetched** — a book that fits in the limit is taken in one request, since being
  willing to hold a whole book is the same as being willing to fetch it in one go. Bigger books
  still work: they stream in pieces and keep only the pages near you, re-fetching the rest as you
  scroll back.

The reader reads the ZIP index first, then pulls pages straight out of the file — the page you are
on first, then the rest in the background. Jumping fetches just that page. Anything the archive
cannot serve (an image format the browser can't decode, a damaged entry, a proxy that strips
`Range` headers) quietly falls back to the normal server endpoint for that page, so you always get
a page.

### Putting a CDN in front

Comic files never change once imported, which makes them ideal to cache at the edge. Two fork-only
endpoints exist so that a CDN can do this without slowing anything down:

| Endpoint | Shape | Cache policy |
| --- | --- | --- |
| `GET /api/v1/books/{id}/file-ranged?v=<token>` | supports HTTP `Range`, answers `206` | `private` — browser may reuse it, no CDN ever holds it |
| `GET /api/v1/books/{id}/file-cached?v=<token>` | always the whole file, one `200`, `Range` not supported | `public, immutable` when `<token>` matches |

The split exists because a CDN caches whole objects. If you let it cache range requests, the very
first one — the reader's 64 KiB probe of the *end* of the ZIP — forces the edge to pull the entire
file from your server before it can answer, and your first page sits behind that. Keeping ranges on
a `private` URL makes that impossible, while the one request that carries the bulk of the bytes goes
to the cacheable URL and streams through on the way into the cache. First read costs the same as
today; every read after that serves the whole archive from the edge instead of your uplink.

On Cloudflare, one cache rule is enough:

- **Expression** — hostname is yours **and** URI path matches `/api/v1/books/*/file-cached`
- **Cache eligibility** — eligible for cache
- **Edge TTL** — *use cache-control header if present*, not a fixed TTL. This is what keeps the
  `<token>` check meaningful; overriding it would cache responses the server marked as non-cacheable.
- **Browser TTL** — respect origin
- **Use strong ETag headers** — on

`<token>` is the file's hash, so it changes whenever the file does and a replaced book gets a new
URL rather than a stale hit.

> **Security note.** A CDN hit is served *without* Komga checking permissions — the URL becomes the
> key to the file. This is safe while `komga.file-hashing` is on (the default), because the token is
> a hash and cannot be guessed. With hashing off it degrades to `mtime-size`, which can be. Set
> `komga-fork.ranged-file-public-cache=false` to never advertise a shared cache; the reader keeps
> working, it just always goes to your server.

## Features

- Browse libraries, series and books via a responsive web UI that works on desktop, tablets and phones
- Organize your library with collections and read lists
- Edit metadata for your series and books
- Import embedded metadata automatically
- Webreader with multiple reading modes
- Manage multiple users, with per-library access control, age restrictions, and labels restrictions
- Offers a REST API, many community tools and scripts can interact with Komga
- OPDS v1 and v2 support
- Kobo Sync with your Kobo eReader
- KOReader Sync
- Download book files, whole series, or read lists
- Duplicate files detection
- Duplicate pages detection and removal
- Import books from outside your libraries directly into your series folder
- Import ComicRack `cbl` read lists

## Installation

Refer to the [website](https://komga.org/docs/category/installation) for instructions.

## Documentation

Head over to our [website](https://komga.org) for more information.

## Develop in Komga

Check the [development guidelines](./DEVELOPING.md).

## Translation

[![Translation status](https://hosted.weblate.org/widgets/komga/-/webui/horizontal-auto.svg)](https://hosted.weblate.org/engage/komga/)

## Powered by

[![Jetbrains_logo](./.github/readme-images/jetbrains.svg)](https://www.jetbrains.com/?from=Komga)

Thanks to [JetBrains](https://www.jetbrains.com/?from=Komga) for providing the development environment that helps us develop Komga.

[![Chromatic logo](https://user-images.githubusercontent.com/321738/84662277-e3db4f80-af1b-11ea-88f5-91d67a5e59f6.png)](https://www.chromatic.com)

Thanks to [Chromatic](https://www.chromatic.com/) for providing the visual testing platform that helps us review UI changes and catch visual regressions.

## Credits

The Komga icon is based on an icon made by [Freepik](https://www.freepik.com/home) from www.flaticon.com
