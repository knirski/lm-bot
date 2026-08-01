package lmbot.shared.domain

opaque type UserId = Long
object UserId:
  def apply(value: Long): UserId = value
  extension (id: UserId) def value: Long = id

/** What the API is willing to say about a user. Deliberately excludes the
  * password hash and anything else the browser has no business seeing.
  */
case class UserView(
    id: UserId,
    username: String,
    displayName: String,
    role: Role,
    telegramLinked: Boolean
)
