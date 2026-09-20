package lmbot.backend.monitor

import gears.async.Async
import lmbot.backend.db.MonitorRow
import lmbot.shared.domain.FoundSlot

/** The engine's Luxmed seam.
  *
  * Production resolves an account client through `AccountClientRegistry` and
  * maps a terms response; tests substitute a fake. Filtering by the monitor's
  * criteria happens in `MonitorCheck`, not here — this returns every slot the
  * search produced.
  */
trait SlotSearch:
  def search(monitor: MonitorRow)(using
      Async
  ): Either[CheckFailure, List[FoundSlot]]
