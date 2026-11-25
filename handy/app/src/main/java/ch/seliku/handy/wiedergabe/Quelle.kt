package ch.seliku.handy.wiedergabe

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.okhttp.OkHttpDataSource
import ch.seliku.handy.Einstellungen
import ch.seliku.handy.Sitzung
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import java.util.concurrent.TimeUnit

/** Was der Spieler abspielt und wie es dem Server gemeldet wird. */
class Quelle(
	val mediaItem: MediaItem,
	val methode: PlayMethod,
	val playSessionId: String?,
	val mediaSourceId: String?,
	val info: MediaSourceInfo?,
)

object Quellen {
	/** Live-TV: Strom öffnen lassen (autoOpenLiveStream); der Server liefert meist HLS. */
	private suspend fun sender(item: BaseItemDto): Quelle {
		val api = Sitzung.api!!
		val pi = api.mediaInfoApi.getPostedPlaybackInfo(itemId = item.id, data = org.jellyfin.sdk.model.api.PlaybackInfoDto(
			userId = Sitzung.benutzerId, autoOpenLiveStream = true, enableDirectPlay = true, enableDirectStream = true, enableTranscoding = true,
			maxStreamingBitrate = Einstellungen.maxBitrate() ?: 20_000_000,
		)).content
		val ms = pi.mediaSources.firstOrNull() ?: error("Kein Strom")
		val url = ms.transcodingUrl?.let { Sitzung.server + it }
			?: "${Sitzung.server}/Videos/${item.id}/stream?static=true&mediaSourceId=${ms.id}" + (ms.liveStreamId?.let { "&liveStreamId=$it" } ?: "")
		val mi = MediaItem.Builder().setUri(url).setMediaId(item.id.toString())
			.apply { if (ms.transcodingUrl?.contains(".m3u8") == true) setMimeType(MimeTypes.APPLICATION_M3U8) }
			.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build()).build()
		return Quelle(mi, if (ms.transcodingUrl != null) PlayMethod.TRANSCODE else PlayMethod.DIRECT_STREAM, pi.playSessionId, ms.id, ms)
	}

	val http: OkHttpClient by lazy {
		OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
	}

	/** Alle Anfragen des Spielers mit Anmeldung im Kopf (der Server lässt keine alten Anmeldewege zu). */
	fun datenquelle(): OkHttpDataSource.Factory = OkHttpDataSource.Factory(http)
		.setDefaultRequestProperties(mapOf("Authorization" to "MediaBrowser Token=\"${Sitzung.token}\""))

	/**
	 * Direkt abspielen (Originaldatei, das Handy dekodiert selbst, Ton notfalls per FFmpeg) oder, wenn [umwandeln]
	 * bzw. eine Bitrate-Grenze gesetzt ist, als HLS vom Server umgewandelt (H.264/AAC Stereo).
	 */
	suspend fun video(item: BaseItemDto, startMs: Long, umwandeln: Boolean, maxBitrate: Int?): Quelle = withContext(Dispatchers.IO) {
		val api = Sitzung.api!!
		if (item.type == org.jellyfin.sdk.model.api.BaseItemKind.TV_CHANNEL) return@withContext sender(item)
		val pi = runCatching { api.mediaInfoApi.getPlaybackInfo(itemId = item.id, userId = Sitzung.benutzerId).content }.getOrNull()
		val ms = pi?.mediaSources?.firstOrNull()
		val msid = ms?.id ?: item.id.toString().replace("-", "")
		val sitzung = pi?.playSessionId
		val server = Sitzung.server
		val id = item.id.toString().replace("-", "")
		val direkt = !umwandeln && maxBitrate == null && (ms?.supportsDirectPlay != false || ms.supportsDirectStream)
		val untertitel = (ms?.mediaStreams ?: emptyList())
			.filter { it.type == MediaStreamType.SUBTITLE && (it.isExternal || it.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL) && direkt }
			.mapNotNull { s ->
				val format = (s.codec ?: "srt").lowercase()
				val (endung, mime) = when (format) {
					"srt", "subrip" -> "srt" to MimeTypes.APPLICATION_SUBRIP
					"ass", "ssa" -> "ass" to MimeTypes.TEXT_SSA
					"vtt", "webvtt" -> "vtt" to MimeTypes.TEXT_VTT
					else -> return@mapNotNull null
				}
				MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse("$server/Videos/$id/$msid/Subtitles/${s.index}/0/Stream.$endung"))
					.setMimeType(mime).setLanguage(s.language).setLabel(s.displayTitle ?: s.title ?: s.language)
					.setId("ext${s.index}")
					.setSelectionFlags(if (s.isDefault) androidx.media3.common.C.SELECTION_FLAG_DEFAULT else 0)
					.build()
			}
		val url = if (direkt) "$server/Videos/$id/stream?static=true&mediaSourceId=$msid" + (sitzung?.let { "&playSessionId=$it" } ?: "")
		else buildString {
			append("$server/Videos/$id/master.m3u8?mediaSourceId=$msid&deviceId=${Sitzung.geraeteId}")
			sitzung?.let { append("&playSessionId=$it") }
			append("&videoCodec=h264&audioCodec=aac,mp3&transcodingMaxAudioChannels=2&segmentContainer=ts&breakOnNonKeyFrames=true")
			append("&maxStreamingBitrate=${maxBitrate ?: 20_000_000}&videoBitRate=${(maxBitrate ?: 20_000_000) - 320_000}&audioBitRate=192000")
			ms?.defaultAudioStreamIndex?.let { append("&audioStreamIndex=$it") }
		}
		val mi = MediaItem.Builder().setUri(url).setMediaId(item.id.toString())
			.apply { if (!direkt) setMimeType(MimeTypes.APPLICATION_M3U8) }
			.setSubtitleConfigurations(untertitel).build()
		Quelle(mi, if (direkt) PlayMethod.DIRECT_PLAY else PlayMethod.TRANSCODE, sitzung, msid, ms)
	}
}
