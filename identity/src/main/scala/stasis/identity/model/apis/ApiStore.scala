package stasis.identity.model.apis

import scala.concurrent.Future
import akka.Done
import stasis.core.persistence.backends.KeyValueBackend
import stasis.identity.model.realms.Realm

trait ApiStore { store =>
  def put(api: Api): Future[Done]
  def delete(realm: Realm.Id, api: Api.Id): Future[Boolean]
  def get(realm: Realm.Id, api: Api.Id): Future[Option[Api]]
  def apis: Future[Map[(Realm.Id, Api.Id), Api]]

  def view: ApiStoreView = new ApiStoreView {
    override def get(realm: Realm.Id, api: Api.Id): Future[Option[Api]] = store.get(realm, api)
    override def apis: Future[Map[(Realm.Id, Api.Id), Api]] = store.apis
  }
}

object ApiStore {
  def apply(backend: KeyValueBackend[(Realm.Id, Api.Id), Api]): ApiStore = new ApiStore {
    override def put(api: Api): Future[Done] = backend.put(api.realm -> api.id, api)
    override def delete(realm: Realm.Id, api: Api.Id): Future[Boolean] = backend.delete(realm, api)
    override def get(realm: Realm.Id, api: Api.Id): Future[Option[Api]] = backend.get(realm, api)
    override def apis: Future[Map[(Realm.Id, Api.Id), Api]] = backend.entries
  }
}