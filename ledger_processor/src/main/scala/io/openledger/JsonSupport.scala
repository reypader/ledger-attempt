package io.openledger

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import io.openledger.http_operations.{AccountResponse, AdjustRequest, OpenAccountRequest, Type}
import spray.json.{DeserializationException, JsArray, JsNumber, JsObject, JsString, JsValue, RootJsonFormat}

trait JsonSupport extends SprayJsonSupport {

  implicit object AccountResponseFormat extends RootJsonFormat[AccountResponse] {
    override def write(account: AccountResponse): JsValue = {
      JsObject(
        "id" -> JsString(account.id),
        "type" -> JsString(account.`type`.name),
        "balance" -> JsObject(
          "available" -> JsNumber(account.balance.get.available),
          "current" -> JsNumber(account.balance.get.current)
        ),
        "accounting_tags" -> JsArray(account.accountingTags.map(JsString(_)).toVector)
      )
    }

    override def read(json: JsValue): AccountResponse = throw new UnsupportedOperationException()
  }

  implicit object OpenAccountRequestFormat extends RootJsonFormat[OpenAccountRequest] {
    override def write(person: OpenAccountRequest): JsValue = throw new UnsupportedOperationException()

    override def read(json: JsValue): OpenAccountRequest = {
      json.asJsObject.getFields("account_type", "account_id", "accounting_tags") match {
        case Seq(JsString(accountType), JsString(accountId), JsArray(jsTags)) => Type.fromName(accountType) match {
          case Some(value) =>
            val tags = jsTags.map {
              case JsString(value) => value
              case _ => throw DeserializationException("Account tags can only be strings")
            }
            OpenAccountRequest(value, accountId, tags)
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

