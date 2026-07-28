package lmbot.backend.auth

import lmbot.backend.db.UserRepo
import lmbot.shared.domain.Role

/** Creates the first admin account, and only ever the first: the credentials
  * are read exclusively when the `users` table is empty (spec §2), so leaving
  * them in the environment cannot silently reset or re-create an admin.
  */
class AdminBootstrap(users: UserRepo):
  import AdminBootstrap.Outcome

  def run(
      adminUsername: Option[String],
      adminPassword: Option[String]
  ): Outcome =
    if users.count() > 0 then Outcome.SkippedUsersExist
    else
      (adminUsername, adminPassword) match
        case (Some(username), Some(password)) =>
          users.insert(username, username, Passwords.hash(password), Role.Admin)
          Outcome.Created(username)
        case _ => Outcome.MissingCredentials

object AdminBootstrap:
  enum Outcome:
    case Created(username: String)
    case SkippedUsersExist
    case MissingCredentials
