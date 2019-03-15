package stasis.test.specs.unit.server.api.requests

import stasis.core.routing.Node
import stasis.server.api.requests.CreateDevicePrivileged
import stasis.server.model.devices.Device
import stasis.server.model.users.User
import stasis.test.specs.unit.UnitSpec

class CreateDevicePrivilegedSpec extends UnitSpec {
  it should "convert requests to devices" in {
    val owner = User(
      id = User.generateId(),
      isActive = true,
      limits = None,
      permissions = Set.empty
    )

    val expectedDevice = Device(
      id = Device.generateId(),
      node = Node.generateId(),
      owner = owner.id,
      isActive = true,
      limits = None
    )

    val privilegedRequest = CreateDevicePrivileged(
      node = expectedDevice.node,
      owner = owner.id,
      limits = expectedDevice.limits
    )

    privilegedRequest.toDevice(owner).copy(id = expectedDevice.id) should be(expectedDevice)
  }
}