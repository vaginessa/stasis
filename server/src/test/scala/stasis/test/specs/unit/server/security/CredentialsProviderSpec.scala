package stasis.test.specs.unit.server.security

import stasis.core.security.jwt.JwtProvider
import stasis.server.security.CredentialsProvider
import stasis.test.specs.unit.AsyncUnitSpec

import scala.concurrent.Future

class CredentialsProviderSpec extends AsyncUnitSpec {
  "A Default CredentialsProvider" should "provide credentials" in {
    val expectedToken = "test-token"
    val expectedScope = "test-scope"

    val underlying = new JwtProvider {
      override def provide(scope: String): Future[String] = Future.successful(s"$expectedToken;$scope")
    }

    val provider = CredentialsProvider.Default(
      scope = expectedScope,
      underlying = underlying
    )

    provider.provide().map { credentials =>
      credentials.token() should be(
        s"$expectedToken;$expectedScope"
      )
    }
  }
}
