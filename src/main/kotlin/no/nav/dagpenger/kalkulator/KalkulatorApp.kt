package no.nav.dagpenger.kalkulator

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.interfaces.Payload
import com.ryanharter.ktor.moshi.moshi
import com.squareup.moshi.JsonDataException
import io.ktor.application.Application
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.auth.Authentication
import io.ktor.auth.authenticate
import io.ktor.auth.authentication
import io.ktor.auth.jwt.JWTPrincipal
import io.ktor.auth.jwt.jwt
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.metrics.micrometer.MicrometerMetrics
import io.ktor.request.path
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.pipeline.PipelineContext
import io.micrometer.core.instrument.Clock
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.slf4j.event.Level
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit

private val LOGGER = KotlinLogging.logger {}
val config = Configuration()

fun main() {
    val jwksUrl: String = if (nyIdporten()) config.application.jwksUrlNy else config.application.jwksUrl
    val jwksIssuer: String = if (nyIdporten()) config.application.jwksIssuerNy else config.application.jwksIssuer

    val jwkProvider = JwkProviderBuilder(URL(jwksUrl))
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    val aktørIdOppslag = PDLKlient(
        config.application.pdlApiBaseUrl,
        config.application.tokenProvider()
    )
    val behovStarter =
        BehovStarter(config.application.dpProxyUrl, config.application.dpProxyTokenProvider())
    val behovStatusPoller =
        BehovStatusPoller(config.application.dpProxyUrl, config.application.dpProxyTokenProvider())
    val subsumsjonFetcher =
        SubsumsjonFetcher(config.application.dpProxyUrl, config.application.dpProxyTokenProvider())
    val dagpengeKalkulator = DagpengeKalkulator(behovStarter, behovStatusPoller, subsumsjonFetcher)

    val application = embeddedServer(Netty, port = config.application.httpPort) {
        KalkulatorApi(jwkProvider, jwksIssuer, aktørIdOppslag, dagpengeKalkulator)
        LOGGER.debug("Starting application")
    }.also {
        it.start(wait = false)
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            application.stop(10000, 60000)
        }
    )
}

private fun nyIdporten() = config.application.idportenDiscoveryUrl.contains("b2clogin.com")

internal fun Application.KalkulatorApi(
    jwkProvider: JwkProvider,
    jwtIssuer: String,
    aktørIdKlient: PDLKlient,
    dagpengerKalkulator: DagpengeKalkulator,
    collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry
) {
    install(ContentNegotiation) {
        moshi(moshiInstance)
    }
    install(CallLogging) {
        level = Level.INFO
        disableDefaultColors()
        filter { call ->
            !call.request.path().startsWith("/isAlive") &&
                !call.request.path().startsWith("/isReady") &&
                !call.request.path().startsWith("/metrics")
        }
    }

    install(MicrometerMetrics) {
        registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT, collectorRegistry, Clock.SYSTEM)
    }

    install(Authentication) {
        jwt {
            verifier(jwkProvider, jwtIssuer)
            realm = "dp-kalkulator-api"
            authHeader {
                val cookie = it.request.cookies["selvbetjening-idtoken"]
                    ?: throw CookieNotSetException("Cookie with name selvbetjening-idtoken not found")
                HttpAuthHeader.Single("Bearer", cookie)
            }
            validate { credentials ->
                when {
                    credentials.payload.getSubjectFromPidOrSub() != null -> JWTPrincipal(credentials.payload)
                    else -> null
                }
            }
        }
    }
    install(StatusPages) {
        exception<Throwable> { cause ->
            LOGGER.info("Generic exception! - ¯\\_(ツ)_/¯", cause)
            LOGGER.error("Unhåndtert feil ved beregning av regel", cause)
            val problem = Problem(
                title = "Uhåndtert feil",
                detail = cause.message
            )
            call.respond(HttpStatusCode.InternalServerError, problem)
        }
        exception<JsonDataException> { cause ->
            LOGGER.info("JsonDataException! - RegelApi godtok ikke parametrene våre", cause)
            LOGGER.warn(cause.message, cause)
            val status = HttpStatusCode.BadRequest
            val problem = Problem(
                type = URI.create("urn:dp:error:parameter"),
                title = "Parameteret er ikke gyldig, mangler obligatorisk felt: '${cause.message}'",
                status = status.value
            )
            call.respond(status, problem)
        }
        exception<CookieNotSetException> { cause ->
            LOGGER.info("CookieNotSetException! (ikke innlogget)", cause)
            val status = HttpStatusCode.Unauthorized
            val problem = Problem(
                title = "Ikke innlogget",
                detail = "${cause.message}",
                status = status.value
            )
            call.respond(status, problem)
        }
        exception<RegelApiBehovHttpClientException> { cause ->
            LOGGER.error("Couldn't get Subsumsjon: " + cause.message, cause)
            val status = HttpStatusCode.BadGateway
            val problem = Problem(
                title = "Feil fra regel-api",
                detail = cause.message,
                status = status.value
            )
            call.respond(status, problem)
        }
        exception<IncompleteResultException> { cause ->
            LOGGER.error("IncompleteResultException: " + cause.message, cause)
            val status = HttpStatusCode.GatewayTimeout
            val problem = Problem(
                title = "Feil fra API, fikk ikke beregnet inntekt",
                detail = cause.message,
                status = status.value
            )
            call.respond(status, problem)
        }
    }
    routing {
        authenticate {
            route("${config.application.basePath}/behov") {
                get {
                    withContext(IO) {
                        val kontekst = call.request.queryParameters["regelkontekst"]
                        requireNotNull(kontekst) { "Regelkontekst må settes" }
                        val fødselsnummer = getSubject()
                        val aktoerId = aktørIdKlient.fetchAktørIdGraphql(fødselsnummer)

                        val response = dagpengerKalkulator.kalkuler(aktoerId, kontekst)

                        call.respond(HttpStatusCode.OK, response)
                    }
                }
            }
            route("${config.application.basePath}/auth") {
                get {
                    call.respond(HttpStatusCode.OK, "Gyldig token!")
                }
            }
        }
        route("${config.application.basePath}/behov/reberegning") {

            post {
                withContext(IO) {
                    val authenticated =
                        call.request.headers["x-api-key"]?.let { it == config.application.forskuddApiKey } ?: false
                    if (!authenticated) {
                        throw CookieNotSetException("Not authenticated")
                    }

                    val request = call.receive<Map<String, String>>()
                    val fnr = request["fnr"] ?: throw JsonDataException("missing request information")

                    val aktoerId = aktørIdKlient.fetchAktørIdGraphql(fnr)
                    val response = dagpengerKalkulator.kalkuler(aktoerId, "corona")
                    call.respond(HttpStatusCode.OK, response)
                }
            }
        }
        naischecks()
        metrics()
    }
}

private fun PipelineContext<Unit, ApplicationCall>.getSubject(): String {
    return runCatching {
        call.authentication.principal?.let {
            (it as JWTPrincipal).payload.getSubjectFromPidOrSub()
        } ?: throw JWTDecodeException("Unable to get subject from JWT")
    }.getOrElse {
        LOGGER.error(it) { "Unable to get subject from authentication" }
        return@getOrElse "UNKNOWN"
    }
}

private fun Payload.getSubjectFromPidOrSub(): String? {
    return this.getClaim("pid").asString() ?: this.getClaim("sub").asString()
}

class CookieNotSetException(override val message: String?) : RuntimeException(message)
