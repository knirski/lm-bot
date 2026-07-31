package lmbot.shared.domain

import java.time.Instant

opaque type AccountId = Long
object AccountId:
  def apply(value: Long): AccountId = value
  extension (id: AccountId) def value: Long = id

enum AccountStatus:
  case Active, AuthFailed, Disabled

object AccountStatus:
  extension (status: AccountStatus)
    def wireName: String = status match
      case Active     => "active"
      case AuthFailed => "auth_failed"
      case Disabled   => "disabled"

  def fromWire(value: String): Either[String, AccountStatus] =
    values.find(_.wireName == value).toRight(s"unknown account status: $value")

final case class AccountView(
    id: AccountId,
    label: String,
    username: String,
    status: AccountStatus,
    statusReason: Option[String],
    lastSuccessfulLogin: Option[Instant]
)

final case class LinkAccountRequest(
    label: String,
    username: String,
    password: String
):
  override def toString: String =
    s"LinkAccountRequest($label, $username, ***)"
