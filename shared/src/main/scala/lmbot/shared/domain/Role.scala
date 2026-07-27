package lmbot.shared.domain

enum Role:
  case Admin, User

object Role:
  def asString(role: Role): String = role match
    case Admin => "admin"
    case User  => "user"

  def fromString(s: String): Option[Role] = s match
    case "admin" => Some(Admin)
    case "user"  => Some(User)
    case _       => None
