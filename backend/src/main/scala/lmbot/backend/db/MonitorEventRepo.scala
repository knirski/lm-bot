package lmbot.backend.db

import java.time.{LocalDateTime, OffsetDateTime}

import com.augustnagro.magnum.{Transactor, connect, sql, transact}
import lmbot.shared.domain.{
  FoundSlot,
  MonitorEventId,
  MonitorEventKind,
  MonitorId
}

/** The append-only monitor log. Slot dedup is the database's job: the partial
  * unique index on `(monitor_id, slot_key) where kind = 'slot_found'` turns
  * "has this slot been seen?" into an insert-on-conflict decision, so two
  * checks racing for the same slot cannot both report it as new.
  */
class MonitorEventRepo(xa: Transactor):

  /** Returns true only when this slot had never been recorded for this monitor.
    */
  def recordSlotFound(
      monitorId: MonitorId,
      slot: FoundSlot,
      at: OffsetDateTime
  ): Boolean = transact(xa):
    sql"""insert into monitor_events
          (monitor_id, kind, slot_key, slot_clinic_id, slot_clinic_name,
           slot_doctor_id, slot_doctor_name, slot_from, slot_to,
           slot_telemedicine, detail, created_at)
          values (${monitorId.value}, 'slot_found', ${slot.key},
                  ${slot.clinicId}, ${slot.clinicName}, ${slot.doctorId},
                  ${slot.doctorName}, ${slot.from}, ${slot.to},
                  ${slot.telemedicine}, null, $at)
          on conflict (monitor_id, slot_key) where kind = 'slot_found'
          do nothing""".update.run() > 0

  /** Appends a non-dedup event. `slot` is present for slot-related kinds such
    * as notification delivery.
    */
  def append(
      monitorId: MonitorId,
      kind: MonitorEventKind,
      slot: Option[FoundSlot],
      detail: Option[String],
      at: OffsetDateTime
  ): MonitorEventId = transact(xa):
    val id =
      sql"""insert into monitor_events
            (monitor_id, kind, slot_key, slot_clinic_id, slot_clinic_name,
             slot_doctor_id, slot_doctor_name, slot_from, slot_to,
             slot_telemedicine, detail, created_at)
            values (${monitorId.value}, ${kind.wireName},
                    ${slot.map(_.key)}, ${slot.map(_.clinicId)},
                    ${slot.flatMap(_.clinicName)}, ${slot.map(_.doctorId)},
                    ${slot.map(_.doctorName)}, ${slot.map(_.from)},
                    ${slot.map(_.to)}, ${slot.map(_.telemedicine)},
                    $detail, $at)
            returning id""".query[Long].run().head
    MonitorEventId(id)

  def listRecent(monitorId: MonitorId, limit: Int): Seq[MonitorEventRow] =
    connect(xa):
      sql"""select * from monitor_events
            where monitor_id = ${monitorId.value}
            order by created_at desc, id desc
            limit $limit""".query[MonitorEventRow].run()
