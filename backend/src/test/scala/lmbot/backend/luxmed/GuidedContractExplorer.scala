package lmbot.backend.luxmed

import java.nio.file.{Files, Paths}
import java.time.{Duration, LocalDate}
import java.util.UUID

import scala.io.StdIn
import scala.util.control.NonFatal

import gears.async.Async
import lmbot.backend.config.{AppVersion, Secret}
import lmbot.backend.luxmed.model.*
import sttp.model.Uri

/** Guided real-API exploration for Plan 3 conformance.
  *
  * This is a manual tool, NOT an MUnit suite. It never runs in CI. Invoke it
  * from an interactive terminal with: sbt "backend/Test/runMain
  * lmbot.backend.luxmed.GuidedContractExplorer"
  *
  * The required flow sends exactly 10 requests (one password grant, one
  * refresh, cities, service variants, terms search, XSRF). An optional
  * lock/release phase adds up to 2 more.
  *
  * Safety limits:
  *   - maximum 12 requests total
  *   - minimum 5-second spacing between requests
  *   - no automatic retry or password fallback
  *   - stops immediately on 401, 409, 429, VersionRejected,
  *     UnexpectedAuthResponse
  *   - spike counter at ~/.lm-bot/spike/contract-exploration-count limits to 2
  *     runs
  */
object GuidedContractExplorer:

  private val spikeCounter =
    Paths.get(
      System.getProperty("user.home"),
      ".lm-bot",
      "spike",
      "contract-exploration-count"
    )
  private val maxRequests = 12
  private val minSpacing = Duration.ofSeconds(5)

  private val processUuid =
    UUID.fromString("00000000-0000-0000-0000-000000000123")
  private val testUuid =
    UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")

  private var requestCount = 0
  private val fingerprints = Vector.newBuilder[WireFingerprint]

  /** Terms found in the search, stored so the lock/release phase can offer
    * pick-by-index instead of requiring manual ID entry.
    */
  private var foundTerms: List[Term] = Nil

  /** XSRF token and cookies from the required phase, reused in optional
    * lock/release to stay within the 12-request budget.
    */
  private var savedXsrfToken: Option[(XsrfToken, CookieJar)] = None

  def main(args: Array[String]): Unit =
    val console = System.console()
    if console == null then
      System.err.println(
        "ERROR: No console available. This tool must be run from an interactive terminal."
      )
      System.err.println(
        "Usage: sbt \"backend/Test/runMain lmbot.backend.luxmed.GuidedContractExplorer\""
      )
      sys.exit(1)

    // Check spike counter — read with absent-file default, reject at limit,
    // then persist the incremented count before the first live request
    val currentCount =
      if Files.exists(spikeCounter) then
        try Files.readString(spikeCounter).trim.toInt
        catch case _: NumberFormatException => 0
      else 0
    if currentCount >= 2 then
      System.err.println(
        "ERROR: Contract exploration already performed twice. " +
          "Reset the counter at ~/.lm-bot/spike/contract-exploration-count to re-run."
      )
      sys.exit(1)
    Files.createDirectories(spikeCounter.getParent)
    Files.writeString(spikeCounter, (currentCount + 1).toString)

    println("=" * 70)
    println("  LUXMED GUIDED CONTRACT EXPLORER")
    println("=" * 70)
    println(s"  Request budget: $maxRequests total (10 required + 2 optional)")
    println(s"  Minimum spacing: ${minSpacing.getSeconds} seconds")
    println(s"  No retries, no automatic fallback")
    println(s"  Spike counter: ${spikeCounter}")
    println("=" * 70)
    println()

    val username = console.readLine("Luxmed username (email): ")
    val passwordChars = console.readPassword("Luxmed password (no echo): ")
    val password = String(passwordChars)
    java.util.Arrays.fill(passwordChars, '\u0000')
    val credentials = Credentials(username, Secret(password))

    // Get base URL
    val baseUrl = console.readLine(
      "Luxmed API base URL [https://portalpacjenta.luxmed.pl]: "
    )
    val actualBase =
      if baseUrl.isBlank then "https://portalpacjenta.luxmed.pl" else baseUrl

    val config = LuxmedConfig(
      oldApi = Uri.unsafeParse(s"$actualBase/PatientPortalMobileAPI/api"),
      newApi = Uri.unsafeParse(s"$actualBase/PatientPortal"),
      appVersion = AppVersion.unsafeFromString("4.44.0"),
      deviceUuid = testUuid
    )

    val transport = LuxmedTransport.production(
      config,
      observer = new WireObserver:
        def observed(fp: WireFingerprint): Unit = fingerprints += fp
    )
    val gate = AccountGate(minSpacing)
    val store = InMemorySessionStore()
    val client = LuxmedClient(transport, credentials, gate, store)

    try
      import gears.async.default.given
      gears.async.Async.fromSync:
        runExploration(client)
      val allFps = fingerprints.result()
      println()
      println("=" * 70)
      println("  CONFORMANCE OK")
      println(s"  Total requests: $requestCount")
      println(s"  One password grant, one refresh, no retries, no mutation")
      println("=" * 70)
      println()
      println(
        "Fingerprint summary (compare against MockConformanceTest expectations):"
      )
      allFps.zipWithIndex.foreach: (fp, i) =>
        println(
          s"  [${i + 1}] status=${fp.status} step=${fp.decodedBody} headers=${fp.headerNames.size} cookies=${fp.cookieNames.size}"
        )
      println()
    catch
      case NonFatal(e) =>
        val allFps = fingerprints.result()
        println()
        println("=" * 70)
        println(s"  EXPLORATION STOPPED at request $requestCount")
        println(s"  Error: ${e.getMessage}")
        println("=" * 70)
        if allFps.nonEmpty then
          println()
          println("Fingerprints collected before failure:")
          allFps.zipWithIndex.foreach: (fp, i) =>
            println(
              s"  [${i + 1}] status=${fp.status} step=${fp.decodedBody} headers=${fp.headerNames.size} cookies=${fp.cookieNames.size}"
            )
        sys.exit(1)

  private def runExploration(
      client: LuxmedClient
  )(using Async): Unit =

    // --- Phase 1: Authenticate (3 requests: password grant, LogInToApp, Reservation) ---
    step("Password grant + LogInToApp + Reservation page")
    val authResult = client.authenticate()
    authResult match
      case Right(session) =>
        requestCount += 3
        println(s"  Authenticated, session expires at ${session.expiresAt}")
      case Left(error) =>
        fail(s"Authentication failed: $error")

    confirmContinue()

    // --- Phase 2: Refresh (3 requests: refresh grant, LogInToApp, Reservation) ---
    step("Refresh grant")
    val refreshResult = client.refreshNowForConformance()
    refreshResult match
      case Right(session) =>
        requestCount += 3
        println(s"  Refreshed, new session expires at ${session.expiresAt}")
      case Left(error) =>
        fail(s"Refresh failed: $error")

    confirmContinue()

    // --- Phase 3: Dictionaries (2 requests) ---
    step("Cities dictionary")
    val citiesResult = client.cities()
    citiesResult match
      case Right(cities) =>
        requestCount += 1
        println(s"  Got ${cities.size} cities")
        cities
          .take(10)
          .foreach: c =>
            println(s"    id=${c.id.value}  name=${c.name}")
        if cities.size > 10 then
          println(s"    ... and ${cities.size - 10} more")
        // Save city IDs for terms search guidance
        val cityIds = cities.map(_.id)
        if cityIds.nonEmpty then
          println(
            s"  City IDs available for search: ${cityIds.map(_.value).mkString(", ")}"
          )
      case Left(error) =>
        fail(s"Cities failed: $error")

    step("Service variants")
    val svResult = client.serviceVariants()
    svResult match
      case Right(variants) =>
        requestCount += 1
        val flat = variants.flatMap(_.flatten)
        println(
          s"  Got ${variants.size} top-level groups, ${flat.size} total variants"
        )
        flat
          .take(15)
          .foreach: v =>
            println(s"    id=${v.id.value}  name=${v.name}")
        if flat.size > 15 then println(s"    ... and ${flat.size - 15} more")
        val svIds = flat.map(_.id)
        println(s"  Service variant IDs: ${svIds.map(_.value).mkString(", ")}")
      case Left(error) =>
        fail(s"Service variants failed: $error")

    confirmContinue()

    // --- Phase 4: Terms search (1 request) ---
    step("Terms search")
    println(
      "  Enter search parameters (use IDs from dictionary display above):"
    )
    val searchCityId = consoleReadLong("  City ID: ")
    val searchSvId = consoleReadLong("  Service variant ID: ")
    val fromDate = consoleReadLine("  Search from (YYYY-MM-DD): ")
    val toDate = consoleReadLine("  Search to (YYYY-MM-DD): ")
    val maybeFacilityId = consoleReadOptionalLong(
      "  Facility ID (optional, press Enter to skip): "
    )
    val maybeDoctorId = consoleReadOptionalLong(
      "  Doctor ID (optional, press Enter to skip): "
    )

    val termsQuery = TermsQuery(
      cityId = CityId(searchCityId),
      serviceVariantId = ServiceVariantId(searchSvId),
      searchDateFrom = LocalDate.parse(fromDate),
      searchDateTo = LocalDate.parse(toDate),
      processId = processUuid,
      facilityIds = maybeFacilityId.map(FacilityId(_)),
      doctorIds = maybeDoctorId.map(DoctorId(_))
    )
    val termsResult = client.searchTerms(termsQuery)
    termsResult match
      case Right(response) =>
        requestCount += 1
        val allTerms =
          response.termsForService.termsForDays.flatMap(_.terms)
        foundTerms = allTerms.toList
        println(
          s"  Got ${response.termsForService.termsForDays.size} days, ${allTerms.size} terms"
        )
        // Display first 20 terms with key fields for selection
        allTerms
          .take(20)
          .zipWithIndex
          .foreach: (t, i) =>
            val docName =
              s"${t.doctor.firstName.getOrElse("")} ${t.doctor.lastName.getOrElse("")}".trim
            val clinic = t.clinic.getOrElse("")
            println(
              s"  [${i + 1}] ${t.dateTimeFrom.value.toLocalDate} ${t.dateTimeFrom.value.toLocalTime}-${t.dateTimeTo.value.toLocalTime}" +
                s"  scheduleId=${t.scheduleId.value}  roomId=${t.roomId}" +
                s"  doctorId=${t.doctor.id.value}${
                    if docName.nonEmpty then s" ($docName)" else ""
                  }" +
                s"${
                    if clinic.nonEmpty
                    then s"  clinicGroupId=${t.clinicGroupId} ($clinic)"
                    else s"  clinicGroupId=${t.clinicGroupId}"
                  }"
            )
        if allTerms.size > 20 then
          println(s"    ... and ${allTerms.size - 20} more terms")
        println(s"  Service variant ID used: ${searchSvId}")
      case Left(error) =>
        fail(s"Terms search failed: $error")

    // --- Phase 5: XSRF token (1 request) ---
    step("XSRF token")
    val xsrfResult = client.getXsrfToken()
    xsrfResult match
      case Right((token, extraCookies)) =>
        requestCount += 1
        savedXsrfToken = Some((token, extraCookies))
        println(
          s"  Got XSRF token (first 20 chars): ${token.token.value.take(20)}..."
        )
      case Left(error) =>
        fail(s"XSRF token failed: $error")

    println()
    println(s"Required 10-step phase complete: $requestCount requests")
    println()

    // --- Optional Phase 6: Lock and Release (up to 2 more requests) ---
    val answer = consoleReadLine(
      "Optional: type 'LOCK AND RELEASE' to perform a lock/release cycle (skip otherwise): "
    ).trim
    if answer == "LOCK AND RELEASE" then performLockRelease(client)

  private def performLockRelease(
      client: LuxmedClient
  )(using Async): Unit =
    if foundTerms.isEmpty then
      println("  No terms from previous search — cannot lock/release.")
      return

    step("Lock term (optional)")
    println("  Pick a term by index from the search results above:")
    val maxIdx = foundTerms.size
    val idx = consoleReadLong(s"  Term index (1-$maxIdx): ").toInt - 1
    if idx < 0 || idx >= maxIdx then
      fail(s"Invalid index: ${idx + 1}, valid range is 1-$maxIdx")

    val term = foundTerms(idx)
    val date = term.dateTimeFrom.value.toLocalDate.toString
    val timeFrom = term.dateTimeFrom.value.toLocalTime.format(
      java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    )
    val timeTo = term.dateTimeTo.value.toLocalTime.format(
      java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    )

    println(s"  Selected term:")
    println(s"    Date: $date")
    println(s"    Time: $timeFrom - $timeTo")
    println(s"    Schedule ID: ${term.scheduleId.value}")
    println(s"    Room ID: ${term.roomId}")
    println(s"    Doctor ID: ${term.doctor.id.value}")
    println(s"    Facility (clinicGroupId): ${term.clinicGroupId}")
    println(s"    Service variant ID: ${term.serviceId.value}")

    // Reuse the XSRF token from the required phase (per Plan 3 spec) to
    // stay within the 12-request budget (10 required + 2 optional).
    val (xsrfToken, extraCookies) = savedXsrfToken.getOrElse:
      fail("No XSRF token from required phase — cannot lock/release.")
    requestCount += 1 // XSRF was already counted in required phase
    val lockRequest = LockTermRequest(
      date = date,
      doctorId = term.doctor.id,
      facilityId = FacilityId(term.clinicGroupId),
      impedimentText = None,
      isAdditional = term.isAdditional,
      isImpediment = term.isImpediment,
      isPreparationRequired = false,
      isTelemedicine = term.isTelemedicine,
      roomId = term.roomId,
      scheduleId = term.scheduleId,
      serviceVariantId = term.serviceId,
      timeFrom = timeFrom,
      timeTo = timeTo
    )
    val lockResult = client.lockTerm(lockRequest, xsrfToken, extraCookies)
    lockResult match
      case Right(response) =>
        requestCount += 1
        val tempId = response.value.temporaryReservationId
        println(s"  Locked, temporary reservation ID: ${tempId.value}")

        // Always release in finally
        try
          println("  Releasing...")
          val releaseResult = client.releaseTerm(
            tempId,
            xsrfToken,
            extraCookies
          )
          releaseResult match
            case Right(()) =>
              requestCount += 1
              println("  Released successfully")
            case Left(error) =>
              println(s"  WARNING: Release failed: $error")
        finally ()
      case Left(error) =>
        fail(s"Lock failed: $error")

  private def step(label: String): Unit =
    println()
    println(s"--- $label ---")

  private def confirmContinue(): Unit =
    val console = System.console()
    if console != null then
      val response =
        console.readLine("Press ENTER to continue or 'q' to quit: ")
      if response.trim.equalsIgnoreCase("q") then
        println("Quit requested.")
        sys.exit(0)

  private def fail(message: String): Nothing =
    println()
    println(s"ERROR: $message")
    sys.exit(1)

  private def consoleReadLine(prompt: String): String =
    val console = System.console()
    if console != null then console.readLine(prompt)
    else
      print(prompt)
      StdIn.readLine()

  private def consoleReadLong(prompt: String): Long =
    val line = consoleReadLine(prompt)
    try line.trim.toLong
    catch
      case _: NumberFormatException =>
        println(s"  Invalid number: $line")
        consoleReadLong(prompt)

  private def consoleReadOptionalLong(prompt: String): Option[Long] =
    val line = consoleReadLine(prompt).trim
    if line.isEmpty then None
    else
      try Some(line.toLong)
      catch
        case _: NumberFormatException =>
          println(s"  Invalid number: $line")
          consoleReadOptionalLong(prompt)
