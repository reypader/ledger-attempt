package io.openledger

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import io.openledger.http_operations.{AccountResponse, AdjustRequest, OpenAccountRequest, Type}
import spray.json.{DeserializationException, JsNumber, JsObject, JsString, JsValue, RootJsonFormat}

trait JsonSupport extends SprayJsonSupport {

  implicit object AccountResponseFormat extends RootJsonFormat[AccountResponse] {
    override def write(person: AccountResponse): JsValue = {
      JsObject(
        "id" -> JsString(person.id),
        "type" -> JsString(person.`type`.name),
        "balance" -> JsObject(
          "available" -> JsNumber(person.balance.get.available),
          "current" -> JsNumber(person.balance.get.current)
        )
      )
    }

    override def read(json: JsValue): AccountResponse = throw new UnsupportedOperationException()
  }

  implicit object OpenAccountRequestFormat extends RootJsonFormat[OpenAccountRequest] {
    override def write(person: OpenAccountRequest): JsValue = throw new UnsupportedOperationException()

    override def read(json: JsValue): OpenAccountRequest = {
      json.asJsObject.getFields("account_type", "account_id") match {
        case Seq(JsString(accountType), JsString(accountId)) => Type.fromName(accountType) match {
          case Some(value) => OpenAccountRequest(value, accountId)
          case None => throw DeserializationException("Unknown Account Type.")
        }
        case _ => throw DeserializationException("Unrecognizable request")
      }
    }
  }

  implicit object AdjustRequestFormat extends RootJsonFormat[AdjustRequest] {
    override def write(person: AdjustRequest): JsValue = throw new UnsupportedOperationException()

    override def read(json: JsValue): AdjustRequest = {
      json.asJsObject.getFields("adjustment_type", "transaction_id", "entry_code", "amount") match {
        case Seq(JsString(adjustmentType), JsString(transactionId), JsString(entryCode), JsNumber(amount)) => Type.fromName(adjustmentType) match {
          case Some(value) => AdjustRequest(value, transactionId, entryCode, amount.doubleValue)
          case None => throw DeserializationException("Unknown Adjustment Type.")
        }
        case _ => throw DeserializationException("Unrecognizable request")
      }
    }
  }

}

