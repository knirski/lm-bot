package lmbot.backend.luxmed.model

/** A closed set of Luxmed API endpoints.
  *
  * Using an enum instead of raw path Strings means the compiler can verify that
  * every endpoint reference is valid, and the transport layer is the single
  * place that converts each case to its URI path segments.
  *
  * The old-API (PatientPortalMobileAPI) and new-API (PatientPortal) split is
  * encoded in which transport method is called, not in the enum itself.
  * Endpoint names follow the spec's endpoint table (§5.4).
  */
enum LuxmedEndpoint(val path: String):

  // -- Old API (PatientPortalMobileAPI) --
  case Token extends LuxmedEndpoint("token")

  // -- New Portal bootstrap --
  case LogInToApp extends LuxmedEndpoint("Account/LogInToApp")
  case ReservationPage extends LuxmedEndpoint("NewPortal/Page/Reservation")

  // -- Dictionaries --
  case Cities extends LuxmedEndpoint("NewPortal/Dictionary/cities")
  case ServiceVariantsGroups
      extends LuxmedEndpoint("NewPortal/Dictionary/serviceVariantsGroups")
  case FacilitiesAndDoctors
      extends LuxmedEndpoint("NewPortal/Dictionary/facilitiesAndDoctors")

  // -- Terms --
  case TermsIndex extends LuxmedEndpoint("NewPortal/terms/index")

  // -- XSRF --
  case ForgeryToken extends LuxmedEndpoint("security/getforgerytoken")

  // -- Reservation mutations (Tasks 6-7) --
  case LockTerm extends LuxmedEndpoint("NewPortal/reservation/lockterm")
  case ConfirmTerm extends LuxmedEndpoint("NewPortal/reservation/confirm")
  case ReleaseTerm extends LuxmedEndpoint("NewPortal/reservation/releaseterm")
  case ChangeTerm extends LuxmedEndpoint("NewPortal/reservation/changeterm")
