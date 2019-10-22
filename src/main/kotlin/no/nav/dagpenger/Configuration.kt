package no.nav.dagpenger

import com.natpryce.konfig.ConfigurationMap
import com.natpryce.konfig.ConfigurationProperties
import com.natpryce.konfig.EnvironmentVariables
import com.natpryce.konfig.Key
import com.natpryce.konfig.intType
import com.natpryce.konfig.overriding
import com.natpryce.konfig.stringType

private val localProperties = ConfigurationMap(
    mapOf(
        "application.profile" to "LOCAL",
        "application.httpPort" to "8099",
        "jwks.url" to "https://localhost",
        "jwks.issuer" to "https://localhost",
            "API_GATEWAY_API_KEY" to "superhemmelig",
            "oppslagBaseUrl" to "http://www.nrk.no",
            "oidcPassword" to "hunter2",
            "oidcUsername" to "Fantomet",
            "oidcStsUrl" to "www.oidc.no"
    )
)
private val devProperties = ConfigurationMap(
    mapOf(
        "jwks.url" to "https://login.microsoftonline.com/navnob2c.onmicrosoft.com/discovery/v2.0/keys?p=b2c_1a_idporten",
        "jwks.issuer" to "https://login.microsoftonline.com/8b7dfc8b-b52e-4741-bde4-d83ea366f94f/v2.0/",
        "application.profile" to "DEV",
        "application.httpPort" to "8099",
            "API_GATEWAY_API_KEY" to "hunter2",
            "oppslagBaseUrl" to "http://www.nrk.no",
            "oidcPassword" to "hunter2",
            "oidcUsername" to "Fantomet",
            "oidcStsUrl" to "www.oidc.no"
    )
)
private val prodProperties = ConfigurationMap(
    mapOf(
        "jwks.url" to "https://login.microsoftonline.com/navnob2c.onmicrosoft.com/discovery/v2.0/keys?p=b2c_1a_idporten",
        "jwks.issuer" to "https://login.microsoftonline.com/8b7dfc8b-b52e-4741-bde4-d83ea366f94f/v2.0/",
        "application.profile" to "PROD",
        "application.httpPort" to "8099",
            "API_GATEWAY_API_KEY" to "hunter2",
            "oppslagBaseUrl" to "http://www.nrk.no",
            "oidcPassword" to "hunter2",
            "oidcUsername" to "Fantomet",
            "oidcStsUrl" to "www.oidc.no"
    )
)

data class Configuration(

    val application: Application = Application()

) {
    data class Application(
        val profile: Profile = config()[Key("application.profile", stringType)].let { Profile.valueOf(it) },
        val httpPort: Int = config()[Key("application.httpPort", intType)],
        val jwksUrl: String = config()[Key("jwks.url", stringType)],
        val jwksIssuer: String = config()[Key("jwks.issuer", stringType)],
        val name: String = "dp-kalkulator-api",
        val apiGatewayBaseUrl: String = config()[Key("API_GATEWAY_URL", stringType)],
        val apiGatewayKey: String = config()[Key("API_GATEWAY_API_KEY", stringType)],
        val oppslagBaseUrl: String = config()[Key("API_GATEWAY_URL"+"dagpenger-oppslag", stringType)],
        val password: String = config()[Key("oidcPassword", stringType)],
        val username: String = config()[Key("oidcUsername", stringType)],
        val oicdStsUrl: String = config()[Key("oidcStsUrl", stringType)]
    )
}

enum class Profile {
    LOCAL, DEV, PROD
}

fun config() = when (System.getenv("NAIS_CLUSTER_NAME") ?: System.getProperty("NAIS_CLUSTER_NAME")) {
    "dev-sbs" -> ConfigurationProperties.systemProperties() overriding EnvironmentVariables overriding devProperties
    "prod-sbs" -> ConfigurationProperties.systemProperties() overriding EnvironmentVariables overriding prodProperties
    else -> {
        ConfigurationProperties.systemProperties() overriding EnvironmentVariables overriding localProperties
    }
}
