package lmbot.backend.luxmed

import lmbot.backend.config.Secret
import lmbot.backend.luxmed.support.{FakeTime, GearsTest, MockLuxmedServer}
import lmbot.backend.luxmed.model.*
import sttp.model.Uri
import scala.concurrent.duration.*
import java.util.UUID

class LuxmedClientAuthTest extends munit.FunSuite with GearsTest:

  private val testUuid = UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")

  private def withClient[T](
      body: (LuxmedClient, MockLuxmedServer) => T
  ): T =
    val mock = MockLuxmedServer()
    try
      val config = LuxmedConfig(
        oldApi = Uri.unsafeParse(s"${mock.baseUri}/PatientPortalMobileAPI/api"),
        newApi = Uri.unsafeParse(s"${mock.baseUri}/PatientPortal"),
        appVersion = "4.44.0",
        deviceUuid = testUuid
      )
      val transport = LuxmedTransport(config)
      val credentials = Credentials("user@example.com", Secret("password123"))
      val fake = FakeTime()
      val gate = AccountGate(0.millis, () => fake.now(), fake.sleeper)
      val store = InMemorySessionStore()
      val client = LuxmedClient(transport, credentials, gate, store)
      body(client, mock)
    finally mock.close()

  test("three-step auth flow succeeds"):
    withClient: (client, mock) =>
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":599,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Set-Cookie" -> "ASP.NET_SessionId=sess1")
      )
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map(
          "Set-Cookie" -> "jwt=JWT1",
          "Authorization-Token" -> "Bearer JWT_TOKEN_1"
        )
      )
      val result = runAsync:
        client.authenticate()
      assert(result.isRight, s"expected success, got $result")
      val session = result.toOption.get
      assertEquals(session.refreshToken.value, "RT1")
      assertEquals(session.jwtToken.value, "JWT_TOKEN_1")

  test("authenticate stores session in the store"):
    withClient: (client, mock) =>
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_1")
      )
      runAsync:
        client.authenticate()
      val stored = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)
      assertEquals(stored, Right("AT1"))

  test("missing JWT in response is ProtocolViolation"):
    withClient: (client, mock) =>
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(status = 200, body = "")
      val result = runAsync:
        client.authenticate()
      assert(result.left.exists:
        case LuxmedError.ProtocolViolation(_) => true
        case _                                => false)

  test("refresh happens when session is expiring"):
    withClient: (client, mock) =>
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":30,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_1")
      )
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT2","expires_in":600,"refresh_token":"RT2","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_2")
      )
      runAsync:
        client.authenticate()
      val result = runAsync:
        client.withSession: (_, session) =>
          Right(session.refreshToken.value)
      assertEquals(result, Right("RT2"))
