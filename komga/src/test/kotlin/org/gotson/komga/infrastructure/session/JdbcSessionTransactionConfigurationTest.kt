package org.gotson.komga.infrastructure.session

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.session.Session
import org.springframework.session.SessionRepository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

@SpringBootTest
class JdbcSessionTransactionConfigurationTest(
  @Autowired private val sessionRepository: SessionRepository<out Session>,
  @Autowired private val mainDataSourceRW: DataSource,
  @Autowired private val transactionManagers: List<PlatformTransactionManager>,
  @Autowired private val transactionTemplates: List<TransactionTemplate>,
  @Autowired private val transactionOperations: List<TransactionOperations>,
  @Autowired @Qualifier("springSessionTransactionOperations") private val sessionTransactionOperations: TransactionOperations,
) {
  private fun <S : Session> SessionRepository<S>.createFindDelete() {
    val session = createSession()
    save(session)
    assertThat(findById(session.id)).isNotNull
    deleteById(session.id)
    assertThat(findById(session.id)).isNull()
  }

  @Test
  // without the fix every call below waits 30 seconds for the main write connection, then fails
  @Timeout(10)
  fun `given main write connection in use when sessions are read and written then they do not wait for it`() {
    assertThat((mainDataSourceRW as HikariDataSource).poolName).isEqualTo("SqliteMainPoolRW")
    assertThat(mainDataSourceRW.maximumPoolSize).isEqualTo(1)

    mainDataSourceRW.connection.use {
      sessionRepository.createFindDelete()
    }
  }

  @Test
  fun `given session transactions on their own database then the main transaction beans are the only ones injected by type`() {
    assertThat(transactionManagers).hasSize(1)
    assertThat(transactionTemplates).hasSize(1)
    assertThat(transactionOperations).containsExactly(transactionTemplates.single())
    assertThat(sessionTransactionOperations).isNotSameAs(transactionTemplates.single())
  }
}
