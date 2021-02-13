package io.openledger.projection

import akka.projection.jdbc.JdbcSession

import java.sql.Connection
import javax.sql.DataSource

class PlainJdbcSession(datasource: DataSource) extends JdbcSession {
  private lazy val conn: Connection = datasource.getConnection
  override def withConnection[Result](func: akka.japi.function.Function[Connection, Result]): Result =
    func(conn)
  override def commit(): Unit = conn.commit()
  override def rollback(): Unit = conn.rollback()
  override def close(): Unit = conn.close()
}
