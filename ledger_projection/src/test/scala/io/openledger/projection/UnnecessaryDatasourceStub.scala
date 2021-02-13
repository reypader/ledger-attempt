package io.openledger.projection

import java.io.{ByteArrayOutputStream, PrintWriter}
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource

class UnnecessaryDatasourceStub(mockConnection: Connection) extends DataSource {
  override def getConnection: Connection = mockConnection

  override def getConnection(username: String, password: String): Connection = throw new UnsupportedOperationException

  override def getLogWriter: PrintWriter = throw new UnsupportedOperationException

  override def setLogWriter(out: PrintWriter): Unit = throw new UnsupportedOperationException

  override def setLoginTimeout(seconds: Int): Unit = throw new UnsupportedOperationException

  override def getLoginTimeout: Int = throw new UnsupportedOperationException

  override def unwrap[T](iface: Class[T]): T = throw new UnsupportedOperationException

  override def isWrapperFor(iface: Class[_]): Boolean = throw new UnsupportedOperationException

  override def getParentLogger: Logger = throw new UnsupportedOperationException
}
