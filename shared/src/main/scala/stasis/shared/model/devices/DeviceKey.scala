package stasis.shared.model.devices

import java.time.Instant

import org.apache.pekko.util.ByteString

import stasis.shared.model.users.User

final case class DeviceKey(
  value: ByteString,
  owner: User.Id,
  device: Device.Id,
  created: Instant
)
