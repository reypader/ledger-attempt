package io.openledger.projection.account

import io.openledger.AccountingMode
import io.openledger.AccountingMode.AccountMode
import io.openledger.projection.account.AccountInfoRepository.AccountInfo
import io.openledger.projection.exceptions.AccountNotFoundException

import java.sql.Connection
import java.time.OffsetDateTime
import scala.util.Using

object AccountInfoRepository {
  final case class AccountInfo(id: String, mode: AccountMode, accountingTag: Set[String], openedOn: OffsetDateTime)

  def apply() = new AccountInfoRepository()
}

//TODO Unit Test
class AccountInfoRepository {
  def save(accoungInfo: AccountInfo)(implicit connection: Connection): Unit = {
    Using.resource(
      connection.prepareStatement(
        "INSERT INTO ledger_account_info " +
          "(account_id, accounting_mode, opened_on) " +
          "VALUES(?, ?, ?);"
      )
    ) { infoStatement =>
      infoStatement.setString(1, accoungInfo.id)
      infoStatement.setString(2, accoungInfo.mode.toString)
      infoStatement.setObject(3, accoungInfo.openedOn)
      infoStatement.execute()
      if (accoungInfo.accountingTag.nonEmpty) {
        Using.resource(
          connection.prepareStatement(
            "INSERT INTO ledger_account_tags " +
              "(account_id, tag) " +
              "VALUES(?, ?);"
          )
        ) { tagStatement =>
          for (tag <- accoungInfo.accountingTag) {
            tagStatement.setString(1, accoungInfo.id)
            tagStatement.setString(2, tag)
            tagStatement.addBatch()
          }
          tagStatement.execute()
        }
      }
    }
  }

  def get(accountId: String)(implicit connection: Connection): AccountInfo = {
    Using.resource(
      connection.prepareStatement(
        "SELECT account_id, accounting_mode, opened_on FROM ledger_account_info WHERE account_id = ?;"
      )
    ) { infoStatement =>
      infoStatement.setString(1, accountId)
      Using.resource(infoStatement.executeQuery()) { infoResult =>
        if (infoResult.next()) {
          val (id, mode, openedOn) =
            (infoResult.getString(1), infoResult.getString(2), infoResult.getObject(3, classOf[OffsetDateTime]))
          Using.resource(
            connection.prepareStatement(
              "SELECT tag FROM ledger_account_tags WHERE account_id = ?;"
            )
          ) { tagStatement =>
            tagStatement.setString(1, accountId)
            Using.resource(tagStatement.executeQuery()) { tagResult =>
              var tags = Set[String]()
              while (tagResult.next()) {
                tags += tagResult.getString(1)
              }
              AccountInfo(id, AccountingMode.withName(mode), tags, openedOn)
            }
          }
        } else {
          throw new AccountNotFoundException(s"Account $accountId not found")
        }
      }
    }
  }
}
