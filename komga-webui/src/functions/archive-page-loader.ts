import {inflateSync} from 'fflate'
import {bookFileRangedUrl} from '@/functions/urls'
import {PageDtoWithUrl} from '@/types/komga-books'
import {
  localHeaderDataOffset,
  locateCentralDirectory,
  matchEntriesToPages,
  METHOD_DEFLATE,
  METHOD_STORE,
  ZIP_TAIL_PROBE_SIZE,
  ZipEntry,
  parseCentralDirectory,
} from '@/functions/zip-directory'

const MB = 1024 * 1024

export interface ArchivePageLoaderOpts {
  bookId: string
  /** cache-buster/ETag token, must match what the server computes for this file */
  versionToken: string
  fileSizeBytes: number
  supportedMediaTypes: string[]
  pages: PageDtoWithUrl[]
  /** blob retention budget in MB; 0 means keep everything */
  budgetMb: number
  /** at or below this size the archive is fetched in one plain GET instead of ranges */
  wholeFileMaxMb: number
  /** 1-based, as used by the reader */
  getCurrentPage: () => number
  applyUrl: (idx: number, url: string) => void
  /** server-side page URL, used only when the archive path cannot serve a page */
  fallbackUrl: (idx: number) => string
  $debug?: (...args: any[]) => void
}

/** Transparent SVG of the page's own dimensions, so layout doesn't jump before decode. */
export function placeholderUrl(page: PageDtoWithUrl): string | null {
  if (!page.width || !page.height) return null
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${page.width}" height="${page.height}"/>`,
  )}`
}

/** Lets the browser paint between page decodes; a full archive is hundreds of them. */
const yieldToBrowser = () => new Promise<void>(resolve => setTimeout(resolve, 0))

/** Sequential reader over a fetch body that tracks its absolute offset in the archive. */
class ByteCursor {
  private buf: Uint8Array | null = null
  private bufPos = 0

  constructor(
    private reader: ReadableStreamDefaultReader<Uint8Array>,
    public pos: number,
  ) {}

  private async fill(): Promise<boolean> {
    while (!this.buf || this.bufPos >= this.buf.length) {
      const {done, value} = await this.reader.read()
      if (done || !value) return false
      this.buf = value
      this.bufPos = 0
    }
    return true
  }

  async read(n: number): Promise<Uint8Array | null> {
    const out = new Uint8Array(n)
    let filled = 0
    while (filled < n) {
      if (!await this.fill()) return null
      const take = Math.min(n - filled, this.buf!.length - this.bufPos)
      out.set(this.buf!.subarray(this.bufPos, this.bufPos + take), filled)
      this.bufPos += take
      filled += take
      this.pos += take
    }
    return out
  }

  async skip(n: number): Promise<boolean> {
    let left = n
    while (left > 0) {
      if (!await this.fill()) return false
      const take = Math.min(left, this.buf!.length - this.bufPos)
      this.bufPos += take
      left -= take
      this.pos += take
    }
    return true
  }

  cancel(): void {
    this.reader.cancel().catch(() => {/* already closed */})
  }
}

interface Target {
  idx: number
  entry: ZipEntry
}

/**
 * Loads reader pages out of the book's own archive instead of the per-page endpoint.
 *
 * The central directory is read first (two small Range requests), which gives the exact
 * byte range of every page. From there:
 *  - the page being read is fetched on its own, so first paint costs one request;
 *  - a background cursor streams forward from that page, then wraps to the beginning,
 *    until the whole archive is resident;
 *  - jumping re-anchors the cursor and issues a priority fetch for the target page.
 *
 * Every failure mode degrades to the server-rendered page URL rather than a blank page:
 * no Range support, an archive the parser rejects, a missing entry, a bad inflate.
 */
export class ArchivePageLoader {
  private controller = new AbortController()
  private disposed = false
  private entries: (ZipEntry | null)[] = []
  private blobUrls: (string | null)[] = []
  private blobSizes: number[] = []
  private ready: boolean[] = []
  private heldBytes = 0
  private anchor = 0
  private generation = 0
  private activeCursor: ByteCursor | null = null
  private inflight = new Set<number>()
  /** set when the server ignored Range and we had to buffer the whole archive */
  private fullBuffer: Uint8Array | null = null
  /** archive is small enough to pull down in one un-ranged GET */
  private wholeMode = false

  constructor(private opts: ArchivePageLoaderOpts) {
    const n = opts.pages.length
    this.blobUrls = new Array(n).fill(null)
    this.blobSizes = new Array(n).fill(0)
    this.ready = new Array(n).fill(false)
  }

  private get budgetBytes(): number {
    return this.opts.budgetMb > 0 ? this.opts.budgetMb * MB : Infinity
  }

  private stopped(gen?: number): boolean {
    return this.disposed || this.controller.signal.aborted || (gen !== undefined && gen !== this.generation)
  }

  private debug(...args: any[]): void {
    this.opts.$debug?.('[archive-page-loader]', ...args)
  }

  private url(): string {
    return bookFileRangedUrl(this.opts.bookId, this.opts.versionToken)
  }

  private fetch(range?: string): Promise<Response> {
    return fetch(this.url(), {
      credentials: 'include',
      signal: this.controller.signal,
      headers: range ? {Range: range} : undefined,
    })
  }

  /** Puts every page that the archive cannot serve back on the server endpoint. */
  private fallbackRemaining(): void {
    for (let i = 0; i < this.opts.pages.length; i++) {
      if (!this.ready[i]) this.opts.applyUrl(i, this.opts.fallbackUrl(i))
    }
  }

  async run(): Promise<void> {
    try {
      if (!await this.loadDirectory()) {
        this.fallbackRemaining()
        return
      }
      this.anchor = Math.max(0, this.opts.getCurrentPage() - 1)
      this.wholeMode = this.opts.fileSizeBytes > 0 && this.opts.fileSizeBytes <= this.opts.wholeFileMaxMb * MB
      // Pages the archive can never satisfy (unsupported codec needing server-side
      // conversion, or no matching entry) go straight back to the server endpoint.
      for (let i = 0; i < this.opts.pages.length; i++) {
        if (!this.decodable(i)) this.opts.applyUrl(i, this.opts.fallbackUrl(i))
      }
      // First paint: don't wait for the sequential pass to reach the current page. In
      // whole-archive mode starting from page one, the pass gets there immediately and a
      // separate request would only cost the CDN a second cache entry.
      if (this.fullBuffer || !this.wholeMode || this.anchor > 0) this.prioritise(this.anchor)
      await this.pump()
    } catch (e: any) {
      if (e?.name !== 'AbortError') {
        this.debug('failed', e)
        this.fallbackRemaining()
      }
    }
  }

  /** Called by the reader whenever the current page changes. */
  onNavigate(pageOneBased: number): void {
    const idx = pageOneBased - 1
    if (this.disposed || idx < 0 || idx >= this.opts.pages.length) return
    this.anchor = idx
    if (this.ready[idx]) return
    this.prioritise(idx)
    // Re-anchor the background cursor so it resumes from where the reader now is. Not in
    // whole-archive mode: that is a single pass over the entire file which will reach
    // every page anyway, so restarting it would just re-download what it already has.
    if (!this.wholeMode && !this.fullBuffer) {
      this.generation++
      this.activeCursor?.cancel()
    }
  }

  private decodable(idx: number): boolean {
    const page = this.opts.pages[idx]
    if (!this.opts.supportedMediaTypes.includes(page.mediaType)) return false
    const entry = this.entries[idx]
    if (!entry) return false
    return entry.method === METHOD_STORE || entry.method === METHOD_DEFLATE
  }

  /**
   * Reads the tail of the archive, then the central directory itself.
   * Returns false when the archive cannot be used at all.
   */
  private async loadDirectory(): Promise<boolean> {
    const size = this.opts.fileSizeBytes
    const probe = Math.min(ZIP_TAIL_PROBE_SIZE, size || ZIP_TAIL_PROBE_SIZE)

    const res = await this.fetch(`bytes=-${probe}`)
    if (!res.ok) {
      this.debug('file-ranged returned', res.status)
      return false
    }

    let tail: Uint8Array
    let tailOffset: number
    if (res.status === 206) {
      tail = new Uint8Array(await res.arrayBuffer())
      tailOffset = (size || tail.length) - tail.length
    } else {
      // Range was ignored — a proxy stripped it, or this is the pre-fork endpoint.
      // We already have the whole file in flight, so keep it and serve from memory.
      this.debug('Range not honoured (status', res.status + '), buffering whole archive')
      this.fullBuffer = new Uint8Array(await res.arrayBuffer())
      tail = this.fullBuffer.subarray(Math.max(0, this.fullBuffer.length - probe))
      tailOffset = this.fullBuffer.length - tail.length
    }
    if (this.stopped()) return false

    let cd: Uint8Array
    try {
      const loc = locateCentralDirectory(tail, tailOffset)
      if (this.fullBuffer) {
        cd = this.fullBuffer.subarray(loc.offset, loc.offset + loc.size)
      } else if (loc.offset >= tailOffset) {
        cd = tail.subarray(loc.offset - tailOffset, loc.offset - tailOffset + loc.size)
      } else {
        const cdRes = await this.fetch(`bytes=${loc.offset}-${loc.offset + loc.size - 1}`)
        if (!cdRes.ok) return false
        cd = new Uint8Array(await cdRes.arrayBuffer())
      }
      this.entries = matchEntriesToPages(parseCentralDirectory(cd), this.opts.pages.map(p => p.fileName))
    } catch (e: any) {
      if (e?.name === 'AbortError') throw e
      this.debug('central directory unusable', e)
      return false
    }

    const matched = this.entries.filter(Boolean).length
    this.debug(`matched ${matched}/${this.opts.pages.length} pages to archive entries`)
    return matched > 0
  }

  /** Single-entry Range fetch, used for the page the reader is actually showing. */
  private async prioritise(idx: number): Promise<void> {
    if (!this.decodable(idx) || this.ready[idx] || this.inflight.has(idx)) return
    const entry = this.entries[idx]!
    this.inflight.add(idx)
    try {
      if (this.fullBuffer) {
        const head = this.fullBuffer.subarray(entry.localHeaderOffset, entry.localHeaderOffset + 30)
        const dataOffset = entry.localHeaderOffset + localHeaderDataOffset(head)
        this.emit(idx, entry, this.fullBuffer.subarray(dataOffset, dataOffset + entry.compressedSize))
        return
      }
      // Over-fetch by the header's own length plus slack for name+extra, so the entry
      // comes back in one round trip; the exact data offset is only known after parsing.
      const slack = 30 + entry.name.length * 4 + 4096
      const from = entry.localHeaderOffset
      const to = from + slack + entry.compressedSize - 1
      const res = await this.fetch(`bytes=${from}-${to}`)
      if (!res.ok || this.stopped()) return
      const buf = new Uint8Array(await res.arrayBuffer())
      const dataOffset = localHeaderDataOffset(buf)
      if (dataOffset < 0 || dataOffset + entry.compressedSize > buf.length) {
        this.debug('priority fetch undershot for page', idx)
        return
      }
      this.emit(idx, entry, buf.subarray(dataOffset, dataOffset + entry.compressedSize))
    } catch (e: any) {
      if (e?.name !== 'AbortError') this.debug('priority fetch failed for page', idx, e)
    } finally {
      this.inflight.delete(idx)
    }
  }

  private async pump(): Promise<void> {
    if (this.fullBuffer) {
      this.drainFullBuffer()
      return
    }
    if (this.wholeMode) {
      await this.streamWhole()
      // Anything the single pass missed (a cancelled stream, a short read) is picked up
      // by the ranged loop, which returns immediately when there is nothing left.
      if (this.stopped()) return
    }
    await this.rangedPump()
  }

  /** The archive is already in memory — decode straight out of it. */
  private drainFullBuffer(): void {
    const buf = this.fullBuffer!
    for (const t of this.allTargets()) {
      if (this.stopped()) return
      const head = buf.subarray(t.entry.localHeaderOffset, t.entry.localHeaderOffset + 30)
      let dataOffset: number
      try {
        dataOffset = t.entry.localHeaderOffset + localHeaderDataOffset(head)
      } catch (e) {
        this.debug('bad local header for page', t.idx, e)
        this.opts.applyUrl(t.idx, this.opts.fallbackUrl(t.idx))
        continue
      }
      this.emit(t.idx, t.entry, buf.subarray(dataOffset, dataOffset + t.entry.compressedSize))
    }
  }

  /**
   * One un-ranged GET over the whole archive. This is the friendliest shape to put in
   * front of a CDN — a single cacheable object rather than a spray of range requests.
   */
  private async streamWhole(): Promise<void> {
    const gen = this.generation
    let cursor: ByteCursor | null = null
    try {
      const targets = this.allTargets()
      if (!targets.length) return
      const res = await this.fetch()
      if (!res.ok || !res.body) {
        this.debug('whole-archive request failed', res.status)
        return
      }
      cursor = new ByteCursor(res.body.getReader(), 0)
      this.activeCursor = cursor
      await this.consume(cursor, targets, gen)
    } catch (e: any) {
      if (e?.name !== 'AbortError' || !this.stopped()) this.debug('whole-archive stream aborted', e)
    } finally {
      cursor?.cancel()
      if (this.activeCursor === cursor) this.activeCursor = null
    }
  }

  /** Background pass: stream forward from the anchor, wrapping until nothing is left. */
  private async rangedPump(): Promise<void> {
    while (!this.stopped()) {
      const gen = this.generation
      const targets = this.targetsFrom(this.anchor)
      if (!targets.length) return
      const loadedBefore = this.loadedCount()

      let cursor: ByteCursor | null = null
      try {
        const start = targets[0].entry.localHeaderOffset
        const res = await this.fetch(`bytes=${start}-`)
        if (!res.ok || !res.body) {
          this.debug('stream request failed', res.status)
          this.fallbackRemaining()
          return
        }
        // A 200 to a ranged request means the body starts at 0, not at `start`.
        cursor = new ByteCursor(res.body.getReader(), res.status === 206 ? start : 0)
        this.activeCursor = cursor
        await this.consume(cursor, targets, gen)
      } catch (e: any) {
        if (e?.name === 'AbortError' && this.stopped()) return
        if (gen === this.generation) {
          this.debug('stream aborted', e)
          this.fallbackRemaining()
          return
        }
      } finally {
        cursor?.cancel()
        if (this.activeCursor === cursor) this.activeCursor = null
      }

      // A pass that decoded nothing and wasn't re-anchored will never make progress;
      // stop rather than spin on the same request forever.
      if (gen === this.generation && this.loadedCount() === loadedBefore) {
        this.debug('stream made no progress, giving up on remaining pages')
        this.fallbackRemaining()
        return
      }
    }
  }

  private loadedCount(): number {
    return this.ready.reduce((n, r) => (r ? n + 1 : n), 0)
  }

  private async consume(cursor: ByteCursor, targets: Target[], gen: number): Promise<void> {
    for (const t of targets) {
      if (this.stopped(gen)) return
      if (this.ready[t.idx]) continue
      const skip = t.entry.localHeaderOffset - cursor.pos
      if (skip < 0) continue
      if (!await cursor.skip(skip)) return
      const head = await cursor.read(30)
      if (!head) return
      let dataOffset: number
      try {
        dataOffset = localHeaderDataOffset(head)
      } catch (e) {
        this.debug('bad local header at', t.entry.localHeaderOffset, e)
        return
      }
      if (!await cursor.skip(dataOffset - 30)) return
      const raw = await cursor.read(t.entry.compressedSize)
      if (!raw) return
      if (this.stopped(gen)) return
      this.emit(t.idx, t.entry, raw)
      await yieldToBrowser()
    }
  }

  /** Every not-yet-loaded page, in archive byte order. */
  private allTargets(): Target[] {
    const out: Target[] = []
    for (let i = 0; i < this.opts.pages.length; i++) {
      if (this.ready[i] || this.inflight.has(i) || !this.decodable(i)) continue
      out.push({idx: i, entry: this.entries[i]!})
    }
    return out.sort((a, b) => a.entry.localHeaderOffset - b.entry.localHeaderOffset)
  }

  /** Not-yet-loaded pages at or after `from`, in archive byte order. */
  private targetsFrom(from: number): Target[] {
    const out: Target[] = []
    const n = this.opts.pages.length
    for (let i = 0; i < n; i++) {
      const idx = (from + i) % n
      if (this.ready[idx] || this.inflight.has(idx) || !this.decodable(idx)) continue
      out.push({idx, entry: this.entries[idx]!})
    }
    if (!out.length) return out
    // One pass can only move forward, so take the run starting at the anchor and let the
    // next iteration of pump() open a fresh request for whatever wrapped around.
    const anchorOffset = out[0].entry.localHeaderOffset
    return out.filter(t => t.entry.localHeaderOffset >= anchorOffset)
      .sort((a, b) => a.entry.localHeaderOffset - b.entry.localHeaderOffset)
  }

  private emit(idx: number, entry: ZipEntry, raw: Uint8Array): void {
    if (this.ready[idx] || this.disposed) return
    let data: Uint8Array
    try {
      data = entry.method === METHOD_DEFLATE ? inflateSync(raw, {out: new Uint8Array(entry.uncompressedSize)}) : raw
    } catch (e) {
      this.debug('inflate failed for page', idx, e)
      this.opts.applyUrl(idx, this.opts.fallbackUrl(idx))
      return
    }
    const blob = new Blob([data], {type: this.opts.pages[idx].mediaType})
    const url = URL.createObjectURL(blob)
    this.blobUrls[idx] = url
    this.blobSizes[idx] = blob.size
    this.ready[idx] = true
    this.heldBytes += blob.size
    this.opts.applyUrl(idx, url)
    this.evictIfOverBudget()
  }

  /**
   * Keeps memory under the configured budget by dropping the resident pages furthest
   * from where the reader is. Dropped pages revert to the server endpoint so they still
   * render instantly if revisited, and get re-fetched from the archive on navigation.
   */
  private evictIfOverBudget(): void {
    if (this.heldBytes <= this.budgetBytes) return
    const current = this.anchor
    const resident = this.ready
      .map((r, i) => (r ? i : -1))
      .filter(i => i >= 0 && Math.abs(i - current) > 2)
      .sort((a, b) => Math.abs(b - current) - Math.abs(a - current))
    for (const idx of resident) {
      if (this.heldBytes <= this.budgetBytes) break
      const url = this.blobUrls[idx]
      if (!url) continue
      URL.revokeObjectURL(url)
      this.heldBytes -= this.blobSizes[idx]
      this.blobUrls[idx] = null
      this.blobSizes[idx] = 0
      this.ready[idx] = false
      this.opts.applyUrl(idx, this.opts.fallbackUrl(idx))
    }
  }

  dispose(): void {
    this.disposed = true
    this.generation++
    this.controller.abort()
    this.activeCursor?.cancel()
    this.activeCursor = null
    this.fullBuffer = null
    this.blobUrls.forEach(u => u && URL.revokeObjectURL(u))
    this.blobUrls.fill(null)
    this.heldBytes = 0
  }
}
