package lmbot.backend.db

import com.augustnagro.magnum.{Transactor, connect, sql, transact}

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

  def findOwned(id: Long, ownerUserId: Long): Option[MonitorRow] = connect(xa):
    sql"""select m.* from monitors m
          join luxmed_accounts a on m.luxmed_account_id = a.id
          where m.id = $id and a.owner_user_id = $ownerUserId"""
      .query[MonitorRow]
      .run()
      .headOption

  def listOwned(ownerUserId: Long): Seq[MonitorRow] = connect(xa):
    sql"""select m.* from monitors m
          join luxmed_accounts a on m.luxmed_account_id = a.id
          where a.owner_user_id = $ownerUserId
          order by m.created_at desc"""
      .query[MonitorRow]
      .run()

  def updateOwned(row: MonitorRow, ownerUserId: Long): Option[MonitorRow] =
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
              and a.owner_user_id = $ownerUserId
            returning m.*"""
        .query[MonitorRow]
        .run()
        .headOption

  def transitionOwned(
      id: Long,
      luxmedAccountId: Long,
      ownerUserId: Long,
      expectedStates: List[String],
      newState: String
  ): Option[MonitorRow] = transact(xa):
    sql"""update monitors m
          set state = $newState, updated_at = now()
          from luxmed_accounts a
          where m.id = $id
            and m.luxmed_account_id = $luxmedAccountId
            and m.luxmed_account_id = a.id
            and a.owner_user_id = $ownerUserId
            and m.state = any($expectedStates)
          returning m.*"""
      .query[MonitorRow]
      .run()
      .headOption

  def deleteOwned(id: Long, ownerUserId: Long): Boolean = transact(xa):
    sql"""delete from monitors m
          using luxmed_accounts a
          where m.luxmed_account_id = a.id
            and m.id = $id
            and a.owner_user_id = $ownerUserId""".update
      .run() > 0
