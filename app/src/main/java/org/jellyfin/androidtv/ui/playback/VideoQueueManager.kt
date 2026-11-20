package org.jellyfin.androidtv.ui.playback

import org.jellyfin.sdk.model.api.BaseItemDto

class VideoQueueManager {
	private var _currentVideoQueue: List<BaseItemDto> = emptyList()
	private var _currentMediaPosition = -1
	private var _lastPlayedAudioLanguageIsoCode: String? = null
	// Seliku: Sprache gilt nur für die Serie, in der sie von Hand gewählt wurde
	private var _lastPlayedAudioSeriesId: java.util.UUID? = null

	fun setCurrentVideoQueue(items: List<BaseItemDto>?) {
		if (items.isNullOrEmpty()) return clearVideoQueue()

		_currentVideoQueue = items.toMutableList()
		_currentMediaPosition = 0
	}

	fun getCurrentVideoQueue(): List<BaseItemDto> = _currentVideoQueue

	fun setCurrentMediaPosition(currentMediaPosition: Int) {
		if (currentMediaPosition !in 0.._currentVideoQueue.size) return

		_currentMediaPosition = currentMediaPosition
	}

	fun getCurrentMediaPosition() = _currentMediaPosition

	fun getLastPlayedAudioLanguageIsoCode(): String? {
		return _lastPlayedAudioLanguageIsoCode
	}

	/** Von Hand gewählte Sprache, aber nur, wenn [item] zur selben Serie gehört (Seliku). */
	fun getAudioLanguageFor(item: BaseItemDto?): String? {
		val serie = item?.seriesId ?: return null
		return if (serie == _lastPlayedAudioSeriesId) _lastPlayedAudioLanguageIsoCode else null
	}

	fun setLastPlayedAudioLanguageIsoCode(isoCode: String) {
		_lastPlayedAudioLanguageIsoCode = isoCode
	}

	/** Seliku: nur bei Wahl von Hand aufrufen; merkt Sprache samt Serie. Filme (ohne Serie) merken nichts. */
	fun setManualAudioLanguage(isoCode: String, item: BaseItemDto?) {
		val serie = item?.seriesId
		_lastPlayedAudioLanguageIsoCode = if (serie != null) isoCode else null
		_lastPlayedAudioSeriesId = serie
	}

	fun clearVideoQueue() {
		_currentVideoQueue = emptyList()
		_currentMediaPosition = -1
		_lastPlayedAudioLanguageIsoCode = null
		_lastPlayedAudioSeriesId = null
	}
}
