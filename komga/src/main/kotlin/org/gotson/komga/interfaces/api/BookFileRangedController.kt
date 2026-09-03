package org.gotson.komga.interfaces.api

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletResponse
import org.apache.commons.io.IOUtils
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.infrastructure.openapi.OpenApiConfiguration
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.infrastructure.web.getMediaTypeOrDefault
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.OutputStream
import java.time.ZoneOffset

private val logger = KotlinLogging.logger {}

private const val CACHE_IMMUTABLE_SHARED = "public, max-age=31536000, immutable"
private const val CACHE_IMMUTABLE_PRIVATE = "private, max-age=31536000, immutable"
private const val CACHE_NONE = "private, no-store"

/**
 * FORK-ONLY endpoints: serve the raw book file to the web reader, which decodes pages out
 * of the archive client-side instead of requesting every page from the server.
 *
 * Why a separate controller instead of patching [CommonBookController.downloadBookFile]:
 * that one returns a `StreamingResponseBody`, which never goes through
 * `ResourceRegionHttpMessageConverter`, so `Range` is silently ignored. Keeping this in
 * its own file with its own routes keeps the fork's merge surface against upstream at
 * zero. The routes are `/file-ranged` and `/file-cached` (not `/file/...`) on purpose:
 * upstream already maps a wildcard under `api/v1/books/{bookId}/file/`, and a sibling
 * under it would be an ambiguous mapping.
 *
 * ## Two URLs, because a CDN caches whole objects
 *
 * The same bytes are exposed twice, with deliberately different contracts, so that the
 * cache policy is a property of *which URL you ask for* rather than of a request header a
 * CDN may not let you match on:
 *
 *  - [getBookFileRanged] — random access. `Range` capable, so it answers 206. Never
 *    marked for a shared cache: on a cold edge, a range request forces the CDN to pull
 *    the entire archive from origin before it can answer even a 64 KiB tail probe, which
 *    would put the whole file in front of first paint. Marked `private` + `immutable`
 *    instead, so the browser may still reuse it but no intermediary holds it.
 *  - [getBookFileCached] — the cacheable object. Always one full 200, `Range` refused by
 *    construction. Streaming it through a cold edge costs nothing extra because the
 *    reader wants the whole archive anyway, and every later read is an edge hit.
 *
 * So the reader sends every ranged request to `/file-ranged` and its single whole-archive
 * pass to `/file-cached`; see `komga-webui/src/functions/archive-page-loader.ts#fetch`.
 * An edge cache rule then only needs a plain path match on `/file-cached` — on Cloudflare,
 * a `wildcard` on `http.request.uri.path` of `/api/v1/books/<any>/file-cached`, with the
 * edge TTL set to *respect* origin `Cache-Control` — that is what makes the `v`
 * check below an actual gate rather than decoration.
 *
 * ## Caching and authorization
 *
 * When the `v` query param matches the current version token of the file, the response is
 * marked immutable — the token is part of the URL, so a re-imported/re-hashed file yields
 * a different URL rather than a stale hit. Note that a CDN edge hit does not go through
 * Komga's authorization: for `/file-cached` the URL effectively becomes a capability, and
 * it is only as unguessable as the token. With `komga.file-hashing` on (the default) that
 * is the file hash; with it off the token degrades to `mtime-size`, which is enumerable.
 * Set `komga-fork.ranged-file-public-cache=false` to never advertise a shared cache (the
 * endpoint keeps working, it just falls back to `private, no-store`).
 */
@RestController
class BookFileRangedController(
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  private val contentRestrictionChecker: ContentRestrictionChecker,
  // @param: keeps the annotation off the backing field — without it Kotlin emits it as a
  // field annotation, which CodingRulesTest rejects as field injection.
  @param:Value("\${komga-fork.ranged-file-public-cache:true}") private val publicCacheEnabled: Boolean,
) {
  @Operation(
    summary = "Download book file (Range capable)",
    description =
      "Fork-only. Same bytes as /file, but served as a Resource so HTTP Range requests are honoured. " +
        "Never marked for a shared cache: use /file-cached for the whole file if you want a CDN to hold it.",
    tags = [OpenApiConfiguration.TagNames.BOOKS],
  )
  @GetMapping("api/v1/books/{bookId}/file-ranged")
  @PreAuthorize("hasRole('FILE_DOWNLOAD')")
  fun getBookFileRanged(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable bookId: String,
    @RequestParam(name = "v", required = false) version: String?,
    response: HttpServletResponse,
  ): ResponseEntity<Resource> {
    val (book, resource) = authorizedFile(principal, bookId)
    val token = book.versionToken()

    setCacheControl(response, if (version == token) CACHE_IMMUTABLE_PRIVATE else CACHE_NONE)

    return ResponseEntity
      .ok()
      .eTag("\"$token\"")
      .contentType(getMediaTypeOrDefault(mediaRepository.findById(book.id).mediaType))
      .body(resource)
  }

  @Operation(
    summary = "Download whole book file (CDN cacheable)",
    description =
      "Fork-only. Same bytes as /file-ranged, but always the whole file in a single 200 — Range is not " +
        "supported. This is the only shape worth putting behind a CDN, so it is the one that carries " +
        "public/immutable when `v` matches the file's current version token.",
    tags = [OpenApiConfiguration.TagNames.BOOKS],
  )
  @GetMapping("api/v1/books/{bookId}/file-cached")
  @PreAuthorize("hasRole('FILE_DOWNLOAD')")
  fun getBookFileCached(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    request: ServletWebRequest,
    @PathVariable bookId: String,
    @RequestParam(name = "v", required = false) version: String?,
    response: HttpServletResponse,
  ): ResponseEntity<StreamingResponseBody> {
    val (book, resource) = authorizedFile(principal, bookId)
    val token = book.versionToken()

    setCacheControl(response, if (publicCacheEnabled && version == token) CACHE_IMMUTABLE_SHARED else CACHE_NONE)
    // Returning a StreamingResponseBody bypasses the ResourceRegion converter, so Range is
    // ignored here by construction. Say so, rather than leaving a client to discover that
    // its Range header was quietly dropped.
    response.setHeader(HttpHeaders.ACCEPT_RANGES, "none")

    // ResponseEntity<StreamingResponseBody> is handled by StreamingResponseBodyReturnValueHandler,
    // which runs before HttpEntityMethodProcessor and so does no conditional-request check of
    // its own — unlike the Resource path above, this one has to answer If-None-Match itself.
    if (request.checkNotModified("\"$token\"")) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build()

    val stream =
      StreamingResponseBody { os: OutputStream ->
        resource.inputStream.use {
          IOUtils.copyLarge(it, os, ByteArray(8192))
          os.close()
        }
      }

    return ResponseEntity
      .ok()
      .eTag("\"$token\"")
      .contentType(getMediaTypeOrDefault(mediaRepository.findById(book.id).mediaType))
      .contentLength(resource.contentLength())
      .body(stream)
  }

  private fun authorizedFile(
    principal: KomgaPrincipal,
    bookId: String,
  ): Pair<Book, FileSystemResource> {
    val book = bookRepository.findByIdOrNull(bookId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    contentRestrictionChecker.checkContentRestrictionBook(principal.user, book)

    val resource = FileSystemResource(book.path)
    if (!resource.exists()) {
      logger.warn { "File not found: $book" }
      throw ResponseStatusException(HttpStatus.NOT_FOUND, "File not found, it may have moved")
    }
    return book to resource
  }

  /**
   * WebMvcConfiguration's WebContentInterceptor already stamped `Cache-Control: private`
   * onto the servlet response for every path under `api`. Overwrite it with setHeader rather
   * than through ResponseEntity.cacheControl(), which would *add* a second, contradicting
   * header instead of replacing this one.
   */
  private fun setCacheControl(
    response: HttpServletResponse,
    value: String,
  ) = response.setHeader(HttpHeaders.CACHE_CONTROL, value)
}

/**
 * Stable identity of the file's current content, used both as ETag and as the `v` cache
 * buster. Falls back to size+mtime when file hashing is disabled (`komga.file-hashing`).
 *
 * The web reader recomputes this from BookDto, so both sides must stay in sync — see
 * `komga-webui/src/functions/urls.ts#bookVersionToken`.
 */
fun Book.versionToken(): String = fileHash.ifBlank { "${fileLastModified.toEpochSecond(ZoneOffset.UTC)}-$fileSize" }
