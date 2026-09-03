import {PageHashKnownDto, PageHashUnknownDto} from '@/types/komga-pagehashes'

const fullUrl = process.env.VUE_APP_KOMGA_API_URL
  ? process.env.VUE_APP_KOMGA_API_URL
  : window.location.origin + window.resourceBaseUrl
const baseUrl = process.env.NODE_ENV === 'production' ? window.resourceBaseUrl : '/'

const urls = {
  origin: !fullUrl.endsWith('/') ? `${fullUrl}/` : fullUrl,
  originNoSlash: fullUrl.endsWith('/') ? fullUrl.slice(0, -1) : fullUrl,
  base: !baseUrl.endsWith('/') ? `${baseUrl}/` : baseUrl,
  baseNoSlash: baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl,
} as Urls

export default urls

export function bookThumbnailUrl(bookId: string): string {
  return `${urls.originNoSlash}/api/v1/books/${bookId}/thumbnail`
}

export function bookThumbnailUrlByThumbnailId(bookId: string, thumbnailId: string) {
  return `${urls.originNoSlash}/api/v1/books/${bookId}/thumbnails/${thumbnailId}`
}

export function bookFileUrl(bookId: string): string {
  return `${urls.originNoSlash}/api/v1/books/${bookId}/file`
}

/**
 * Fork-only Range-capable variant of {@link bookFileUrl}, used by the archive page
 * loader for random access. `v` identifies the file's current content: it busts caches
 * when the file is replaced, and tells the server the response is safe to mark immutable.
 *
 * Marked `private` by the server, never shared-cacheable: on a cold CDN edge a range
 * request forces the whole archive to be pulled from origin before even a tail probe can
 * be answered, which would put the entire file in front of first paint. Use
 * {@link bookFileCachedUrl} for the whole-archive pass instead.
 */
export function bookFileRangedUrl(bookId: string, version: string): string {
  return `${urls.originNoSlash}/api/v1/books/${bookId}/file-ranged?v=${encodeURIComponent(version)}`
}

/**
 * Fork-only companion to {@link bookFileRangedUrl}: same bytes, but always the whole file
 * in a single 200 — Range is not supported. That is the only shape worth putting behind a
 * CDN, so this is the URL the server marks `public, immutable` (when `v` matches) and the
 * one an edge cache rule should match on.
 */
export function bookFileCachedUrl(bookId: string, version: string): string {
  return `${urls.originNoSlash}/api/v1/books/${bookId}/file-cached?v=${encodeURIComponent(version)}`
}

/**
 * Must produce the same string as `Book.versionToken()` on the server
 * (komga/src/main/kotlin/org/gotson/komga/interfaces/api/BookFileRangedController.kt).
 */
export function bookVersionToken(book: { fileHash?: string, fileLastModified?: Date | string, sizeBytes: number }): string {
  if (book.fileHash) return book.fileHash
  const epochSeconds = Math.floor(new Date(book.fileLastModified as any).getTime() / 1000)
  return `${epochSeconds}-${book.sizeBytes}`
}

export function bookPageUrl(bookId: string, page: number, convertTo?: string): string {
  let url = `${urls.originNoSlash}/api/v1/books/${bookId}/pages/${page}`
  if (convertTo) {
    url += `?convert=${convertTo}`
  }
  return url
}

export function bookPageThumbnailUrl(bookId: string, page: number): string {
  return `${urls.originNoSlash}/api/v1/books/${bookId}/pages/${page}/thumbnail`
}

export function bookManifestUrl(bookId: string): string {
  return `${urls.originNoSlash}/api/v1/books/${bookId}/manifest`
}

export function bookPositionsUrl(bookId: string): string {
  return `${urls.originNoSlash}/api/v1/books/${bookId}/positions`
}
export function seriesFileUrl(seriesId: string): string {
  return `${urls.originNoSlash}/api/v1/series/${seriesId}/file`
}

export function seriesThumbnailUrl(seriesId: string): string {
  return `${urls.originNoSlash}/api/v1/series/${seriesId}/thumbnail`
}

export function seriesThumbnailUrlByThumbnailId(seriesId: string, thumbnailId: string) {
  return `${urls.originNoSlash}/api/v1/series/${seriesId}/thumbnails/${thumbnailId}`
}

export function collectionThumbnailUrl(collectionId: string): string {
  return `${urls.originNoSlash}/api/v1/collections/${collectionId}/thumbnail`
}

export function collectionThumbnailUrlByThumbnailId(collectionId: string, thumbnailId: string) {
  return `${urls.originNoSlash}/api/v1/collections/${collectionId}/thumbnails/${thumbnailId}`
}

export function readListThumbnailUrl(readListId: string): string {
  return `${urls.originNoSlash}/api/v1/readlists/${readListId}/thumbnail`
}

export function readListFileUrl(readListId: string): string {
  return `${urls.originNoSlash}/api/v1/readlists/${readListId}/file`
}

export function readListThumbnailUrlByThumbnailId(readListId: string, thumbnailId: string) {
  return `${urls.originNoSlash}/api/v1/readlists/${readListId}/thumbnails/${thumbnailId}`
}

export function transientBookPageUrl(transientBookId: string, page: number): string {
  return `${urls.originNoSlash}/api/v1/transient-books/${transientBookId}/pages/${page}`
}

export function pageHashUnknownThumbnailUrl(pageHash: PageHashUnknownDto, resize?: number): string {
  let url = `${urls.originNoSlash}/api/v1/page-hashes/unknown/${pageHash.hash}/thumbnail`
  if(resize) {
    url += `?resize=${resize}`
  }
  return url
}

export function pageHashKnownThumbnailUrl(pageHash: PageHashKnownDto): string {
  return `${urls.originNoSlash}/api/v1/page-hashes/${pageHash.hash}/thumbnail`
}
