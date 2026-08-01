package lmbot.backend.db

import com.augustnagro.magnum.{Transactor, connect, sql, transact}
import lmbot.shared.domain.{AccountId, MonitorId, MonitorState, UserId}

class MonitorRepo(xa: Transactor):

  def reserveId(): Long = transact(xa):
    sql"select nextval('monitor_id_seq')".query[Long].run().head

  def insert(row: MonitorRow): MonitorRow = transact(xa):
    sql"""insert into monitors
          (id, luxmed_account_id, name,
           city_id, city_name, service_id, service_name,
           facility_ids, facility_names, doctor_ids, doctor_names,
           date_from, date_to, time_from, time_to,
           days_of_week, auto_book, interval_minutes, state,
           created_at, updated_at)
          values (${row.id}, ${row.luxmedAccountId}, ${row.name},
                  ${row.cityId}, ${row.cityName},
                  ${row.serviceId}, ${row.serviceName},
                  ${row.facilityIds}, ${row.facilityNames},
                  ${row.doctorIds}, ${row.doctorNames},
                  ${row.dateFrom}, ${row.dateTo},
                  ${row.timeFrom}, ${row.timeTo},
                  ${row.daysOfWeek}, ${row.autoBook},
                  ${row.intervalMinutes}, ${row.state},
                  ${row.createdAt}, ${row.updatedAt})
          returning *"""
      .query[MonitorRow]
      .run()
      .head

  def findOwned(id: MonitorId, ownerUserId: UserId): Option[MonitorRow] =
    connect(xa):
      sql"""select m.* from monitors m
          join luxmed_accounts a on m.luxmed_account_id = a.id
          where m.id = ${id.value} and a.owner_user_id = ${ownerUserId.value}"""
        .query[MonitorRow]
        .run()
        .headOption

  def listOwned(ownerUserId: UserId): Seq[MonitorRow] = connect(xa):
    sql"""select m.* from monitors m
          join luxmed_accounts a on m.luxmed_account_id = a.id
          where a.owner_user_id = ${ownerUserId.value}
          order by m.created_at desc"""
      .query[MonitorRow]
      .run()

  def updateOwned(row: MonitorRow, ownerUserId: UserId): Option[MonitorRow] =
    transact(xa):
      sql"""update monitors m
            set name = ${row.name},
                city_id = ${row.cityId},
                city_name = ${row.cityName},
                service_id = ${row.serviceId},
                service_name = ${row.serviceName},
                facility_ids = ${row.facilityIds},
                facility_names = ${row.facilityNames},
                doctor_ids = ${row.doctorIds},
                doctor_names = ${row.doctorNames},
                date_from = ${row.dateFrom},
                date_to = ${row.dateTo},
                time_from = ${row.timeFrom},
                time_to = ${row.timeTo},
                days_of_week = ${row.daysOfWeek},
                auto_book = ${row.autoBook},
                interval_minutes = ${row.intervalMinutes},
                state = ${row.state},
                updated_at = now()
            from luxmed_accounts a
      where m.id = ${row.id}
              and m.luxmed_account_id = a.id
              and a.owner_user_id = ${ownerUserId.value}
            returning m.*"""
        .query[MonitorRow]
        .run()
        .headOption

  def transitionOwned(
      id: MonitorId,
      luxmedAccountId: AccountId,
      ownerUserId: UserId,
      expectedStates: List[MonitorState],
      newState: MonitorState
  ): Option[MonitorRow] =
    val expectedWireNames = expectedStates.map(_.wireName)
    val newWireName = newState.wireName
    transact(xa):
      sql"""update monitors m
            set state = $newWireName, updated_at = now()
            from luxmed_accounts a
            where m.id = ${id.value}
              and m.luxmed_account_id = ${luxmedAccountId.value}
              and m.luxmed_account_id = a.id
              and a.owner_user_id = ${ownerUserId.value}
              and m.state = any($expectedWireNames)
            returning m.*"""
        .query[MonitorRow]
        .run()
        .headOption

  def deleteOwned(id: MonitorId, ownerUserId: UserId): Boolean = transact(xa):
    sql"""delete from monitors m
          using luxmed_accounts a
          where m.luxmed_account_id = a.id
            and m.id = ${id.value}
            and a.owner_user_id = ${ownerUserId.value}""".update
      .run() > 0
