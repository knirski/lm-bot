package lmbot.backend.luxmed.model

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}

/** Opaque domain IDs that prevent Long mix-ups between different entity types.
  *
  * Each is a compile-time distinct wrapper around Long with a jsoniter codec so
  * they transparently serialise to/from the wire.
  */

opaque type DoctorId = Long

object DoctorId:
  def apply(value: Long): DoctorId = value
  extension (id: DoctorId) def value: Long = id

  given JsonValueCodec[DoctorId] with
    def decodeValue(in: JsonReader, default: DoctorId): DoctorId =
      DoctorId(in.readLong())
    def encodeValue(x: DoctorId, out: JsonWriter): Unit =
      out.writeVal(x.value)
    def nullValue: DoctorId = null.asInstanceOf[DoctorId]

opaque type FacilityId = Long

object FacilityId:
  def apply(value: Long): FacilityId = value
  extension (id: FacilityId) def value: Long = id

  given JsonValueCodec[FacilityId] with
    def decodeValue(in: JsonReader, default: FacilityId): FacilityId =
      FacilityId(in.readLong())
    def encodeValue(x: FacilityId, out: JsonWriter): Unit =
      out.writeVal(x.value)
    def nullValue: FacilityId = null.asInstanceOf[FacilityId]

opaque type ScheduleId = Long

object ScheduleId:
  def apply(value: Long): ScheduleId = value
  extension (id: ScheduleId) def value: Long = id

  given JsonValueCodec[ScheduleId] with
    def decodeValue(in: JsonReader, default: ScheduleId): ScheduleId =
      ScheduleId(in.readLong())
    def encodeValue(x: ScheduleId, out: JsonWriter): Unit =
      out.writeVal(x.value)
    def nullValue: ScheduleId = null.asInstanceOf[ScheduleId]

opaque type ServiceVariantId = Long

object ServiceVariantId:
  def apply(value: Long): ServiceVariantId = value
  extension (id: ServiceVariantId) def value: Long = id

  given JsonValueCodec[ServiceVariantId] with
    def decodeValue(
        in: JsonReader,
        default: ServiceVariantId
    ): ServiceVariantId =
      ServiceVariantId(in.readLong())
    def encodeValue(x: ServiceVariantId, out: JsonWriter): Unit =
      out.writeVal(x.value)
    def nullValue: ServiceVariantId = null.asInstanceOf[ServiceVariantId]

opaque type ReservationId = Long

object ReservationId:
  def apply(value: Long): ReservationId = value
  extension (id: ReservationId) def value: Long = id

  given JsonValueCodec[ReservationId] with
    def decodeValue(in: JsonReader, default: ReservationId): ReservationId =
      ReservationId(in.readLong())
    def encodeValue(x: ReservationId, out: JsonWriter): Unit =
      out.writeVal(x.value)
    def nullValue: ReservationId = null.asInstanceOf[ReservationId]
