/**
 * Minimal ZIP central-directory reader.
 *
 * Reading the central directory (rather than scanning local headers front to back) is
 * what makes random access possible: it gives the exact byte range of every entry up
 * front, so a page can be fetched with a single Range request, and a sequential scan
 * never has to guess where an entry ends. It also sidesteps the data-descriptor problem
 * — entries written with flag bit 3 carry sizes of 0 in their *local* header, but the
 * central directory always has the real values.
 *
 * Only what CBZ/CBR-as-zip needs is implemented: stored + deflate, ZIP64, UTF-8 and
 * CP437 names. Multi-disk archives and encrypted entries are rejected.
 */

const EOCD_SIG = 0x06054b50
const EOCD64_SIG = 0x06064b50
const EOCD64_LOCATOR_SIG = 0x07064b58
const CDFH_SIG = 0x02014b50
const LFH_SIG = 0x04034b50

const EOCD_MIN_SIZE = 22
/** ZIP comment is a uint16 length, so the EOCD can be at most this far from the end. */
export const ZIP_TAIL_PROBE_SIZE = 22 + 0xffff

export const METHOD_STORE = 0
export const METHOD_DEFLATE = 8

export interface ZipEntry {
  name: string
  method: number
  compressedSize: number
  uncompressedSize: number
  /** absolute offset of the entry's local file header within the archive */
  localHeaderOffset: number
}

export interface ZipDirectoryLocation {
  offset: number
  size: number
}

export class ZipFormatError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ZipFormatError'
  }
}

const u16 = (v: DataView, o: number) => v.getUint16(o, true)
const u32 = (v: DataView, o: number) => v.getUint32(o, true)

/** Reads a 64-bit little-endian value, rejecting anything above Number.MAX_SAFE_INTEGER. */
function u64(v: DataView, o: number): number {
  const value = v.getBigUint64(o, true)
  if (value > BigInt(Number.MAX_SAFE_INTEGER)) throw new ZipFormatError('64-bit value exceeds safe integer range')
  return Number(value)
}

const utf8Decoder = new TextDecoder('utf-8')
// CP437 is the ZIP spec's default for names without the UTF-8 flag. Only the high half
// differs from latin1; the low half is ASCII.
// 0x80..0xFF, sixteen code points per line.
export const CP437_HIGH =
  'ÇüéâäàåçêëèïîìÄÅ' +
  'ÉæÆôöòûùÿÖÜ¢£¥₧ƒ' +
  'áíóúñÑªº¿⌐¬½¼¡«»' +
  '░▒▓│┤╡╢╖╕╣║╗╝╜╛┐' +
  '└┴┬├─┼╞╟╚╔╩╦╠═╬╧' +
  '╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀' +
  'αßΓπΣσµτΦΘΩδ∞φε∩' +
  '≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ '

function decodeName(bytes: Uint8Array, utf8: boolean): string {
  if (utf8) return utf8Decoder.decode(bytes)
  let out = ''
  for (let i = 0; i < bytes.length; i++) {
    const b = bytes[i]
    out += b < 0x80 ? String.fromCharCode(b) : CP437_HIGH[b - 0x80]
  }
  return out
}

/**
 * Locates the central directory from the last {@link ZIP_TAIL_PROBE_SIZE} bytes of the
 * archive. `tailOffset` is the absolute offset at which `tail` starts.
 */
export function locateCentralDirectory(tail: Uint8Array, tailOffset: number): ZipDirectoryLocation {
  const view = new DataView(tail.buffer, tail.byteOffset, tail.byteLength)

  let eocd = -1
  for (let i = tail.length - EOCD_MIN_SIZE; i >= 0; i--) {
    if (u32(view, i) === EOCD_SIG) {
      eocd = i
      break
    }
  }
  if (eocd < 0) throw new ZipFormatError('end of central directory record not found')

  let size = u32(view, eocd + 12)
  let offset = u32(view, eocd + 16)
  const entryCount = u16(view, eocd + 10)

  // Any of these being saturated means the real values live in a ZIP64 record.
  if (offset === 0xffffffff || size === 0xffffffff || entryCount === 0xffff) {
    const locator = eocd - 20
    if (locator < 0 || u32(view, locator) !== EOCD64_LOCATOR_SIG) throw new ZipFormatError('ZIP64 locator not found')
    const eocd64 = u64(view, locator + 8) - tailOffset
    if (eocd64 < 0 || eocd64 + 56 > tail.length) throw new ZipFormatError('ZIP64 record outside probed tail')
    if (u32(view, eocd64) !== EOCD64_SIG) throw new ZipFormatError('bad ZIP64 record signature')
    size = u64(view, eocd64 + 40)
    offset = u64(view, eocd64 + 48)
  }

  if (size <= 0 || offset < 0) throw new ZipFormatError('empty or invalid central directory')
  return {offset, size}
}

/** Parses a central directory blob into entries, in archive order. */
export function parseCentralDirectory(cd: Uint8Array): ZipEntry[] {
  const view = new DataView(cd.buffer, cd.byteOffset, cd.byteLength)
  const entries: ZipEntry[] = []

  let p = 0
  while (p + 46 <= cd.length && u32(view, p) === CDFH_SIG) {
    const flags = u16(view, p + 8)
    const method = u16(view, p + 10)
    const nameLen = u16(view, p + 28)
    const extraLen = u16(view, p + 30)
    const commentLen = u16(view, p + 32)

    let compressedSize = u32(view, p + 20)
    let uncompressedSize = u32(view, p + 24)
    let localHeaderOffset = u32(view, p + 42)

    const nameStart = p + 46
    const extraStart = nameStart + nameLen
    if (extraStart + extraLen + commentLen > cd.length) throw new ZipFormatError('central directory entry overruns buffer')

    // ZIP64 extended information: present fields appear in a fixed order, and only for
    // the ones saturated in the fixed-size record.
    if (uncompressedSize === 0xffffffff || compressedSize === 0xffffffff || localHeaderOffset === 0xffffffff) {
      let e = extraStart
      const extraEnd = extraStart + extraLen
      while (e + 4 <= extraEnd) {
        const headerId = u16(view, e)
        const dataSize = u16(view, e + 2)
        if (headerId === 0x0001) {
          let f = e + 4
          if (uncompressedSize === 0xffffffff) { uncompressedSize = u64(view, f); f += 8 }
          if (compressedSize === 0xffffffff) { compressedSize = u64(view, f); f += 8 }
          if (localHeaderOffset === 0xffffffff) { localHeaderOffset = u64(view, f); f += 8 }
          break
        }
        e += 4 + dataSize
      }
    }

    if ((flags & 0x0001) !== 0) throw new ZipFormatError('encrypted entries are not supported')

    entries.push({
      name: decodeName(cd.subarray(nameStart, extraStart), (flags & 0x0800) !== 0),
      method,
      compressedSize,
      uncompressedSize,
      localHeaderOffset,
    })

    p = extraStart + extraLen + commentLen
  }

  if (entries.length === 0) throw new ZipFormatError('no entries in central directory')
  return entries
}

/**
 * Given the first bytes of a local file header, returns how many bytes precede the
 * entry's compressed data. Returns -1 if `head` is too short to tell (the variable-length
 * name/extra fields mean the header is 30..30+128KB bytes long).
 */
export function localHeaderDataOffset(head: Uint8Array): number {
  if (head.length < 30) return -1
  const view = new DataView(head.buffer, head.byteOffset, head.byteLength)
  if (u32(view, 0) !== LFH_SIG) throw new ZipFormatError('bad local file header signature')
  return 30 + u16(view, 26) + u16(view, 28)
}

/**
 * Matches page filenames (as reported by Komga, which reads the archive server-side)
 * onto central directory entries.
 *
 * Exact name is the normal path. The fallbacks cover archives whose names Komga and the
 * browser decode differently (non-UTF8 names) — first by basename, then positionally
 * among the entries that look like files, which holds because both sides enumerate the
 * central directory in the same order.
 */
export function matchEntriesToPages(entries: ZipEntry[], pageFileNames: string[]): (ZipEntry | null)[] {
  const byName = new Map<string, ZipEntry>()
  const byBase = new Map<string, ZipEntry>()
  for (const e of entries) {
    if (!byName.has(e.name)) byName.set(e.name, e)
    const base = e.name.substring(e.name.lastIndexOf('/') + 1)
    if (base && !byBase.has(base)) byBase.set(base, e)
  }
  const files = entries.filter(e => !e.name.endsWith('/'))

  return pageFileNames.map((name, i) => {
    const normalized = name.replace(/^\.\//, '')
    const hit = byName.get(name) || byName.get(normalized) ||
      byBase.get(normalized.substring(normalized.lastIndexOf('/') + 1))
    if (hit) return hit
    return files.length === pageFileNames.length ? files[i] : null
  })
}
