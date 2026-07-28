package lmbot.backend.luxmed

import sttp.model.Uri
import java.util.UUID

/** Configuration for the Luxmed API client.
  *
  * @param oldApi
  *   Base URI for the legacy PatientPortalMobileAPI (OAuth token endpoint).
  * @param newApi
  *   Base URI for the PatientPortal NewPortal API (all authenticated calls).
  * @param appVersion
  *   The app version string sent in Custom-User-Agent. Defaults to "4.44.0".
  * @param deviceUuid
  *   Stable device UUID for the Custom-User-Agent header.
  * @param apiLevel
  *   Android API level sent in the Custom-User-Agent.
  * @param deviceModel
  *   Device model name sent in the Custom-User-Agent.
  */
final case class LuxmedConfig(
    oldApi: Uri,
    newApi: Uri,
    appVersion: String,
    deviceUuid: UUID,
    apiLevel: Int = 33,
    deviceModel: String = "Samsung Galaxy S23"
)
