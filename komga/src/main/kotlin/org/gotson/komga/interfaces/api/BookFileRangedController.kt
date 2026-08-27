package org.gotson.komga.interfaces.api

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletResponse
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
import org.springframework.web.server.ResponseStatusException
import java.time.ZoneOffset

private val logger = KotlinLogging.logger {}

/**
 * FORK-ONLY endpoint: serves the raw book file with HTTP Range support, so the web
 * reader can stream the archive and decode pages client-side instead of requesting
 * every page from the server.
 *
 * Why a separate controller instead of patching [CommonBookController.downloadBookFile]:
 *  - that one returns a `StreamingResponseBody`, which never goes through
 *    `ResourceRegionHttpMessageConverter`, so `Range` is silently ignored;
 *  - keeping this in its own file with its own route keeps the fork's merge surface
 *    against upstream at zero.
 *
 * The route is `/file-ranged` (not `/file/ranged`) on purpose: upstream already maps a
 * wildcard under `api/v1/books/{bookId}/file/`, and a sibling under it would be an
 * ambiguous mapping.
 *
 * Caching: when the `v` query param matches the current version token of the file, the
 * response is marked immutable so a CDN can cache it — the token is part of the URL, so
 * a re-imported/re-hashed file yields a different URL rather than a stale hit. Note that
 * a CDN edge hit does not go through Komga's authorization; the URL effectively becomes a
 * capability. Set `komga-fork.ranged-file-public-cache=false` to disable that entirely
 * (the endpoint keeps working, it just never advertises a shared cache).
 */
@RestController
class BookFileRangedController(
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  private val contentRestrictionChecker: ContentRestrictionChecker,
  @Value("\${komga-fork.ranged-file-public-cache:true}") private val publicCacheEnabled: Boolean,
) {
  @Operation(
    summary = "Download book file (Range capable)",
    description = "Fork-only. Same bytes as /file, but served as a Resource so HTTP Range requests are honoured.",
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
    val book = bookRepository.findByIdOrNull(bookId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    contentRestrictionChecker.checkContentRestrictionBook(principal.user, book)

    val resource = FileSystemResource(book.path)
    if (!resource.exists()) {
      logger.warn { "File not found: $book" }
      throw ResponseStatusException(HttpStatus.NOT_FOUND, "File not found, it may have moved")
    }

    val token = book.versionToken()

    // WebMvcConfiguration's WebContentInterceptor already stamped `Cache-Control: private`
    // onto the servlet response for every /api/** path. Overwrite it here with setHeader
    // rather than through ResponseEntity.cacheControl(), which would *add* a second,
    // contradicting header instead of replacing this one.
    response.setHeader(
      HttpHeaders.CACHE_CONTROL,
      if (publicCacheEnabled && version != null && version == token)
        "public, max-age=31536000, immutable"
      else
        "private, no-store",
    )

    return ResponseEntity
      .ok()
      .eTag("\"$token\"")
      .contentType(getMediaTypeOrDefault(mediaRepository.findById(book.id).mediaType))
      .body(resource)
  }
}

/**
 * Stable identity of the file's current content, used both as ETag and as the `v` cache
 * buster. Falls back to size+mtime when file hashing is disabled (`komga.file-hashing`).
 *
 * The web reader recomputes this from BookDto, so both sides must stay in sync — see
 * `komga-webui/src/functions/urls.ts#bookVersionToken`.
 */
fun Book.versionToken(): String = fileHash.ifBlank { "${fileLastModified.toEpochSecond(ZoneOffset.UTC)}-$fileSize" }
