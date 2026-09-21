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

  /** Slots whose most recent delivery attempt failed and that have attempts
    * left. The next check retries exactly these, so a transient outage does not
    * silently lose a notification (at-most-three attempts per slot).
    */
  def slotsAwaitingDelivery(
      monitorId: MonitorId,
      maxAttempts: Int
  ): Seq[FoundSlot] = connect(xa):
    sql"""select e.*
          from (
            select slot_key, count(*) as attempts
            from monitor_events
            where monitor_id = ${monitorId.value}
              and kind = 'notification_failed'
            group by slot_key
          ) failures
          join lateral (
            select * from monitor_events latest
            where latest.monitor_id = ${monitorId.value}
              and latest.slot_key = failures.slot_key
              and latest.kind in ('notification_sent', 'notification_failed')
            order by latest.created_at desc, latest.id desc
            limit 1
          ) e on true
          where e.kind = 'notification_failed'
            and failures.attempts < $maxAttempts"""
      .query[MonitorEventRow]
      .run()
      .flatMap(MonitorEventRepo.toFoundSlot)

object MonitorEventRepo:

  /** The structured slot a row carries, when it has one. The slot columns are
    * all-or-none (a database constraint), so the first present value means the
    * slot is present.
    */
  def toFoundSlot(row: MonitorEventRow): Option[FoundSlot] =
    for
      key <- row.slotKey
      clinicId <- row.slotClinicId
      doctorId <- row.slotDoctorId
      from <- row.slotFrom
      to <- row.slotTo
      telemedicine <- row.slotTelemedicine
    yield FoundSlot(
      key = key,
      clinicId = clinicId,
      clinicName = row.slotClinicName,
      doctorId = doctorId,
      doctorName = row.slotDoctorName.getOrElse(""),
      from = from,
      to = to,
      telemedicine = telemedicine
    )
