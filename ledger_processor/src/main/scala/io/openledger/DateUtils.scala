package io.openledger

import java.time.{OffsetDateTime, ZoneOffset}

object DateUtils {
  type TimeGen = () => OffsetDateTime

  def now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)
}
