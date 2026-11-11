package org.gotson.komga.infrastructure.unicode

import com.ibm.icu.text.Collator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CollatorsConcurrencyTest {
  private val threads = 8

  // long enough, and mixing scripts and accents, so that every comparison goes through the collator's shared buffer
  private val titles =
    (1..400).map { "Les Misérables – 進撃の巨人 第${it}巻 – Ångström naïve façade" } +
      (1..400).map { "les miserables – 進撃の巨人 第${it}巻 – angstrom naive facade" }

  private fun sortConcurrently(collator: Collator): List<Result<List<String>>> {
    val executor = Executors.newFixedThreadPool(threads)
    try {
      val barrier = CyclicBarrier(threads)
      return executor
        .invokeAll(
          (1..threads).map { seed ->
            Callable {
              barrier.await(10, TimeUnit.SECONDS)
              runCatching {
                (1..20).map { round -> titles.shuffled(java.util.Random((seed * 100 + round).toLong())).sortedWith { a, b -> collator.compare(a, b) } }.last()
              }
            }
          },
        ).map { it.get(60, TimeUnit.SECONDS) }
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `given shared collators when sorting from several threads then every thread gets a correctly sorted list`() {
    listOf(Collators.collator1, Collators.collator3).forEach { collator ->
      assertThat(collator.isFrozen).isTrue

      val results = sortConcurrently(collator)

      assertThat(results).hasSize(threads)
      assertThat(results).allSatisfy { result ->
        assertThat(result.exceptionOrNull()).isNull()
        assertThat(result.getOrThrow()).containsExactlyInAnyOrderElementsOf(titles)
        assertThat(result.getOrThrow()).isSortedAccordingTo { a, b -> collator.compare(a, b) }
      }
    }
  }
}
