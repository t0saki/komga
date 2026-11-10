package org.gotson.komga.infrastructure.session

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.session.jdbc.config.annotation.SpringSessionDataSource
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * FORK-ONLY: makes Spring Session run its transactions on the sessions database.
 *
 * Spring Session wraps every session read, save and cleanup in a transaction. When it is
 * not given a transaction manager of its own, it takes the only one in the context, and
 * that one belongs to the *main* database: each authenticated request (every API call,
 * every thumbnail) then borrows the single write connection of the main database about
 * three times, only to begin and commit a transaction in which nothing is executed, since
 * the session statements run on the sessions datasource. Whenever that connection is held
 * by a library scan or any other long write, all requests queue behind it and fail after
 * 30 seconds with `SqliteMainPoolRW - Connection is not available`.
 *
 * Spring Session uses the bean named `springSessionTransactionOperations` when there is
 * one. Three things are deliberate here:
 *  - the datasource is resolved the way Spring Session resolves its own, so that the
 *    transaction and the session statements always share one connection.
 *  - the transaction manager is not a bean: a second one in the context would make Spring
 *    Boot back off from configuring the main one.
 *  - the bean is not a default candidate, only its qualifier finds it: the domain services
 *    inject the main `TransactionTemplate` by type, and Spring Boot only configures one
 *    when it sees no other.
 */
@Configuration
@ConditionalOnProperty(
  value = ["komga.jdbc-session-enabled"],
  havingValue = "true",
  matchIfMissing = false,
)
class JdbcSessionTransactionConfiguration {
  @Bean(name = ["springSessionTransactionOperations"], defaultCandidate = false)
  fun springSessionTransactionOperations(
    @SpringSessionDataSource springSessionDataSource: ObjectProvider<DataSource>,
    dataSource: ObjectProvider<DataSource>,
  ): TransactionOperations =
    TransactionTemplate(JdbcTransactionManager(springSessionDataSource.getIfAvailable() ?: dataSource.getObject())).apply {
      // same propagation as the template Spring Session builds for itself
      propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
}
