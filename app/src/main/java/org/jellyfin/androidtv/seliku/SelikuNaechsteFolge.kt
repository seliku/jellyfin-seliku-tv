package org.jellyfin.androidtv.seliku

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

/**
 * Ende einer Folge, aber in der Warteschlange steht nichts mehr: die nächste Folge beim Server nachfragen.
 *
 * Normalerweise legt [Abspielen.video] die ganze Serie in die Warteschlange. Klappt das beim Start nicht (Server langsam,
 * Folge nicht in der Liste gefunden), lief nur diese eine Folge – und am Ende ging es zurück auf die Startseite statt
 * auf «Als Nächstes» (S 15.11.). Jetzt wird am Ende noch einmal nachgesehen.
 */
object NaechsteFolge {
	@JvmStatic
	fun nachladen(item: BaseItemDto, fertig: java.util.function.Consumer<BaseItemDto?>) {
		val serie = item.seriesId
		if (item.type != BaseItemKind.EPISODE || serie == null) { fertig.accept(null); return }
		val api = org.koin.java.KoinJavaComponent.get<ApiClient>(ApiClient::class.java)
		CoroutineScope(Dispatchers.Main).launch {
			val naechste = runCatching {
				withContext(Dispatchers.IO) {
					val alle = api.tvShowsApi.getEpisodes(seriesId = serie, isMissing = false,
						fields = org.jellyfin.androidtv.data.repository.ItemRepository.itemFields).content.items
					val nr = alle.indexOfFirst { it.id == item.id }
					if (nr >= 0) alle.getOrNull(nr + 1) else null
				}
			}.onFailure { Timber.w(it, "Nächste Folge nachladen") }.getOrNull()
			Timber.i("Nächste Folge nachgeladen: %s", naechste?.id)
			fertig.accept(naechste)
		}
	}
}
