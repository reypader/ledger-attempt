package io.openledger.api.http

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import io.openledger.AccountingMode
import io.openledger.api.http.Operations.{AccountResponse, AdjustRequest, Balance, OpenAccountRequest}
import spray.json._

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

  implicit val accountModeFormat: EnumJsonFormat[AccountingMode.type] = new EnumJsonFormat(AccountingMode)
  implicit val balanceFormat: RootJsonFormat[Balance] = jsonFormat2(Balance)
  implicit val accountResponseFormat: RootJsonFormat[AccountResponse] = jsonFormat4(AccountResponse)
  implicit val openAccountRequestFormat: RootJsonFormat[OpenAccountRequest] = jsonFormat3(OpenAccountRequest)
  implicit val adjustRequestFormat: RootJsonFormat[AdjustRequest] = jsonFormat4(AdjustRequest)

}

// From https://github.com/spray/spray-json/pull/336/files#diff-752180eb8dc62c50aabe89619582363f65cb62ee49284c846d82ed7fe156cd1bR173
class EnumJsonFormat[T <: scala.Enumeration](enu: T) extends RootJsonFormat[T#Value] {
  override def write(obj: T#Value): JsValue = JsString(obj.toString)

  override def read(json: JsValue): T#Value = {
    json match {
      case JsString(txt) => enu.withName(txt)
      case somethingElse => throw DeserializationException(s"Expected a value from enum $enu instead of $somethingElse")
    }
  }
}