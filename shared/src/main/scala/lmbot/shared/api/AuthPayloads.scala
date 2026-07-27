package lmbot.shared.api

case class LoginRequest(username: String, password: String):
  override def toString: String = s"LoginRequest($username, ***)"
