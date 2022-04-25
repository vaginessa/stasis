package stasis.test.specs.unit.core.persistence.nodes

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior, SpawnProtocol}
import stasis.core.networking.http.HttpEndpointAddress
import stasis.core.persistence.StoreInitializationResult
import stasis.core.persistence.backends.memory.MemoryBackend
import stasis.core.persistence.nodes.NodeStore
import stasis.core.routing.Node
import stasis.test.specs.unit.AsyncUnitSpec

class NodeStoreSpec extends AsyncUnitSpec {

  "A NodeStore with caching disabled" should behave like nodeStore(cachingEnabled = false)

  "A NodeStore with caching enabled" should behave like nodeStore(cachingEnabled = true)

  def nodeStore(cachingEnabled: Boolean): Unit = {
    it should "add, retrieve and delete nodes" in {
      val store = createStore(cachingEnabled)

      val expectedNode = Node.Remote.Http(
        id = Node.generateId(),
        address = HttpEndpointAddress("localhost"),
        storageAllowed = true
      )

      for {
        _ <- store.put(expectedNode)
        actualNode <- store.get(expectedNode.id)
        actualNodeExists <- store.contains(expectedNode.id)
        someNodes <- store.nodes
        _ <- store.delete(expectedNode.id)
        missingNode <- store.get(expectedNode.id)
        missingNodeExists <- store.contains(expectedNode.id)
        noNodes <- store.nodes
      } yield {
        actualNode should be(Some(expectedNode))
        actualNodeExists should be(true)
        someNodes should be(Map(expectedNode.id -> expectedNode))
        missingNode should be(None)
        missingNodeExists should be(false)
        noNodes should be(Map.empty)
      }
    }

    it should "provide a read-only view" in {
      val store = createStore(cachingEnabled)
      val storeView = store.view

      val expectedNode = Node.Remote.Http(
        id = Node.generateId(),
        address = HttpEndpointAddress("localhost"),
        storageAllowed = true
      )

      for {
        _ <- store.put(expectedNode)
        actualNode <- storeView.get(expectedNode.id)
        actualNodeExists <- storeView.contains(expectedNode.id)
        someNodes <- storeView.nodes
        _ <- store.delete(expectedNode.id)
        missingNode <- storeView.get(expectedNode.id)
        missingNodeExists <- storeView.contains(expectedNode.id)
        noNodes <- storeView.nodes
      } yield {
        actualNode should be(Some(expectedNode))
        actualNodeExists should be(true)
        someNodes should be(Map(expectedNode.id -> expectedNode))
        missingNode should be(None)
        missingNodeExists should be(false)
        noNodes should be(Map.empty)
        a[ClassCastException] should be thrownBy { val _ = storeView.asInstanceOf[NodeStore] }
      }
    }
  }

  private implicit val system: ActorSystem[SpawnProtocol.Command] = ActorSystem(
    Behaviors.setup(_ => SpawnProtocol()): Behavior[SpawnProtocol.Command],
    "NodeStoreSpec"
  )

  private def createStore(cachingEnabled: Boolean): NodeStore = {
    val StoreInitializationResult(store, init) = NodeStore(
      MemoryBackend[Node.Id, Node](name = s"node-store-${java.util.UUID.randomUUID()}"),
      cachingEnabled = cachingEnabled
    )

    val _ = init().await

    store
  }
}
