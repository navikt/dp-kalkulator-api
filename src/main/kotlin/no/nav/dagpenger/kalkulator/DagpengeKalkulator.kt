package no.nav.dagpenger.kalkulator

import io.prometheus.client.Counter
import mu.KotlinLogging

private val LOGGER = KotlinLogging.logger {}

private val resultatCounter = Counter
        .build()
        .name("kalkulator_minsteinntekt")
        .help("Oppfyller krav til minsteinntekt fra kalkulator")
        .labelNames("resultat")
        .register()

class DagpengeKalkulator(
    private val behovStarter: BehovStarter,
    private val behovStatusPoller: BehovStatusPoller,
    private val subsumsjonFetcher: SubsumsjonFetcher
) {
    suspend fun kalkuler(aktørId: String): KalkulasjonsResult {
        LOGGER.info { "starting behov, trying " + config.application.regelApiBaseUrl + "/behov" }
        val pollLocation = behovStarter.startBehov(aktørId)
        LOGGER.info("Location: $pollLocation")

        val subsumsjonLocation = behovStatusPoller.pollStatus(pollLocation)
        LOGGER.info("Subsumsjon: $subsumsjonLocation")

        val subsumsjon = subsumsjonFetcher.getSubsumsjon(subsumsjonLocation)

        val oppfyllerMinsteinntekt = subsumsjon.minsteinntektResultat?.oppfyllerMinsteinntekt
                ?: throw IncompleteResultException("Missing minsteinntektResultat")

        val periode =
                subsumsjon.periodeResultat?.periodeAntallUker
                        ?: throw IncompleteResultException("Missing periodeResultat")

        if (oppfyllerMinsteinntekt) {
            resultatCounter.labels("true").inc()
        } else {
            resultatCounter.labels("false").inc()
        }

        return KalkulasjonsResult(
                oppfyllerMinsteinntekt,
                subsumsjon.satsResultat?.ukesats ?: throw IncompleteResultException("Missing satsResultat"),
                periode,
                subsumsjon.grunnlagResultat?.beregningsregel ?: throw IncompleteResultException("Missing beregningsregel")
        )
    }
}

class IncompleteResultException(override val message: String) : RuntimeException(message)

data class KalkulasjonsResult(
    val oppfyllerMinsteinntekt: Boolean,
    val ukesats: Int,
    val periodeAntallUker: Int,
    val regelBrukt: String
)
