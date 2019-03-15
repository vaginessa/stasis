package stasis.test.specs.unit.core.persistence.nodes

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior, SpawnProtocol}
import stasis.core.networking.http.HttpEndpointAddress
import stasis.core.persistence.backends.memory.MemoryBackend
import stasis.core.persistence.nodes.NodeStore
import stasis.core.routing.Node
import stasis.test.specs.unit.AsyncUnitSpec

class NodeStoreSpec extends AsyncUnitSpec {

  private implicit val system: ActorSystem[SpawnProtocol] = ActorSystem(
    Behaviors.setup(_ => SpawnProtocol.behavior): Behavior[SpawnProtocol],
    "NodeStoreSpec"
  )

  private def createStore(): NodeStore = NodeStore(
    MemoryBackend[Node.Id, Node](name = s"node-store-${java.util.UUID.randomUUID()}")
  )

  "A NodeStore" should "add, retrieve and delete nodes" in {
    val store = createStore()

    val expectedNode = Node.Remote.Http(
      id = Node.generateId(),
      address = HttpEndpointAddress("localhost")
    )

    for {
      _ <- store.put(expectedNode)
      actualNode <- store.get(expectedNode.id)
      someNodes <- store.nodes
      _ <- store.delete(expectedNode.id)
      missingNode <- store.get(expectedNode.id)
      noNodes <- store.nodes
    } yield {
      actualNode should be(Some(expectedNode))
      someNodes should be(Map(expectedNode.id -> expectedNode))
      missingNode should be(None)
      noNodes should be(Map.empty)
    }
  }

  it should "provide a read-only view" in {
    val store = createStore()
    val storeView = store.view

    val expectedNode = Node.Remote.Http(
      id = Node.generateId(),
      address = HttpEndpointAddress("localhost")
    )

    for {
      _ <- store.put(expectedNode)
      actualNode <- storeView.get(expectedNode.id)
      someNodes <- storeView.nodes
      _ <- store.delete(expectedNode.id)
      missingNode <- storeView.get(expectedNode.id)
      noNodes <- storeView.nodes
    } yield {
      actualNode should be(Some(expectedNode))
      someNodes should be(Map(expectedNode.id -> expectedNode))
      missingNode should be(None)
      noNodes should be(Map.empty)
      a[ClassCastException] should be thrownBy { val _ = storeView.asInstanceOf[NodeStore] }
    }
  }
}