package lmbot.shared.domain

/** What the API is willing to say about a user. Deliberately excludes the
  * password hash and anything else the browser has no business seeing.
  */
case class UserView(
    id: Long,
    username: String,
    displayName: String,
    role: Role,
    telegramLinked: Boolean
)
