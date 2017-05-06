package com.dhpcs.liquidity.persistence

import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence.PersistenceSpec._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.serialization.ProtoConverter
import org.scalatest.FreeSpec

object PersistenceSpec {

  val zoneCreatedEvent = ZoneCreatedEvent(
    timestamp = ModelSpec.zone.created,
    zone = ModelSpec.zone
  )

  val zoneCreatedEventProto = proto.persistence.ZoneCreatedEvent(
    timestamp = ModelSpec.zoneProto.created,
    zone = Some(ModelSpec.zoneProto)
  )

}

class PersistenceSpec extends FreeSpec {

  "A ZoneCreatedEvent" - {
    s"will convert to $zoneCreatedEventProto" in assert(
      ProtoConverter[ZoneCreatedEvent, proto.persistence.ZoneCreatedEvent]
        .asProto(zoneCreatedEvent) === zoneCreatedEventProto
    )
    s"will convert from $zoneCreatedEvent" in assert(
      ProtoConverter[ZoneCreatedEvent, proto.persistence.ZoneCreatedEvent]
        .asScala(zoneCreatedEventProto) === zoneCreatedEvent
    )
  }
}