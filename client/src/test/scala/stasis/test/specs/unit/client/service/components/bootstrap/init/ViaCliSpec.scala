package stasis.test.specs.unit.client.service.components.bootstrap.init

import stasis.client.service.ApplicationArguments
import stasis.client.service.components.bootstrap.init.ViaCli
import stasis.test.specs.unit.AsyncUnitSpec

class ViaCliSpec extends AsyncUnitSpec {
  "An Init via CLI" should "support retrieving bootstrap mode arguments" in {
    val expectedArgs = ApplicationArguments.Mode.Bootstrap(
      serverBootstrapUrl = "https://test-url",
      bootstrapCode = "test-code",
      acceptSelfSignedCertificates = false,
      userPassword = Array.emptyCharArray
    )

    ViaCli
      .retrieveArguments(args = expectedArgs)
      .map { actualArgs =>
        actualArgs should be(expectedArgs)
      }
  }

  it should "fail if invalid server bootstrap URL is provided" in {
    val expectedArgs = ApplicationArguments.Mode.Bootstrap(
      serverBootstrapUrl = "http://test-url",
      bootstrapCode = "test-code",
      acceptSelfSignedCertificates = false,
      userPassword = Array.emptyCharArray
    )

    ViaCli
      .retrieveArguments(args = expectedArgs)
      .failed
      .map { e =>
        e.getMessage should include("Server bootstrap URL must be provided and must use HTTPS")
      }
  }

  it should "fail if no or empty bootstrap code is provided" in {
    val expectedArgs = ApplicationArguments.Mode.Bootstrap(
      serverBootstrapUrl = "https://test-url",
      bootstrapCode = "",
      acceptSelfSignedCertificates = false,
      userPassword = Array.emptyCharArray
    )

    ViaCli
      .retrieveArguments(args = expectedArgs)
      .failed
      .map { e =>
        e.getMessage should include("Bootstrap code must be provided")
      }
  }

  it should "support retrieving credentials" in {
    val expectedArgs = ApplicationArguments.Mode.Bootstrap(
      serverBootstrapUrl = "https://test-url",
      bootstrapCode = "test-code",
      acceptSelfSignedCertificates = false,
      userPassword = "test-password".toCharArray
    )

    ViaCli
      .retrieveCredentials(args = expectedArgs)
      .map { actualPassword =>
        actualPassword.mkString should be(expectedArgs.userPassword.mkString)
      }
  }

  it should "fail to retrieve credentials if no user password is provided" in {
    val expectedArgs = ApplicationArguments.Mode.Bootstrap(
      serverBootstrapUrl = "https://test-url",
      bootstrapCode = "test-code",
      acceptSelfSignedCertificates = false,
      userPassword = Array.emptyCharArray
    )

    ViaCli
      .retrieveCredentials(args = expectedArgs)
      .failed
      .map { e =>
        e.getMessage should include("User password cannot be empty")
      }
  }
}
