package stasis.test.specs.unit.identity.api.manage

import akka.http.scaladsl.model.StatusCodes
import stasis.identity.api.Formats._
import stasis.identity.api.manage.Apis
import stasis.identity.api.manage.requests.CreateApi
import stasis.identity.model.apis.Api
import stasis.test.specs.unit.identity.RouteTest
import stasis.test.specs.unit.identity.model.Generators

import scala.concurrent.Future

class ApisSpec extends RouteTest {
  import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._

  "Apis routes" should "respond with all APIs" in {
    val store = createApiStore()
    val realm = Generators.generateRealmId
    val apis = new Apis(store)

    val expectedApis = Generators
      .generateSeq(min = 2, g = Generators.generateApi)
      .map(_.copy(realm = realm))

    Future.sequence(expectedApis.map(store.put)).await
    Get() ~> apis.routes(user, realm) ~> check {
      status should be(StatusCodes.OK)
      responseAs[Seq[Api]].sortBy(_.id) should be(expectedApis.sortBy(_.id))
    }
  }

  they should "create new APIs" in {
    val store = createApiStore()
    val realm = Generators.generateRealmId
    val apis = new Apis(store)

    val request = CreateApi(id = "some-api")

    Post().withEntity(request) ~> apis.routes(user, realm) ~> check {
      status should be(StatusCodes.OK)
      store.get(realm, request.id).await should be(Some(request.toApi(realm)))
    }
  }

  they should "respond with existing APIs" in {
    val store = createApiStore()
    val realm = Generators.generateRealmId
    val apis = new Apis(store)

    val expectedApi = Generators.generateApi.copy(realm = realm)

    store.put(expectedApi).await
    Get(s"/${expectedApi.id}") ~> apis.routes(user, realm) ~> check {
      status should be(StatusCodes.OK)
      responseAs[Api] should be(expectedApi)
    }
  }

  they should "delete existing APIs" in {
    val store = createApiStore()
    val realm = Generators.generateRealmId
    val apis = new Apis(store)

    val api = Generators.generateApi.copy(realm = realm)

    store.put(api).await
    Delete(s"/${api.id}") ~> apis.routes(user, realm) ~> check {
      status should be(StatusCodes.OK)
      store.apis.await should be(Map.empty)
    }
  }

  they should "not delete missing APIs" in {
    val store = createApiStore()
    val realm = Generators.generateRealmId
    val apis = new Apis(store)

    Delete("/some-api") ~> apis.routes(user, realm) ~> check {
      status should be(StatusCodes.NotFound)
    }
  }

  private val user = "some-user"
}