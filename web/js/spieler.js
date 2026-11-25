// Videowiedergabe im Browser: Original direkt, wenn der Browser es kann (MP4/WebM mit H.264/VP9/AV1 und AAC/MP3/Opus),
// sonst HLS vom Server (Video meist nur umverpackt). Fortschritt an Jellyfin, Vorspann/Abspann wie am TV,
// nächste Folge mit Countdown, Tonspur je Serie gemerkt (nur bei Wahl von Hand), Glas-Uhr.
import { api, sitzung, q } from './api.js';
import { h, zeit, meldung } from './ui.js';
import * as E from './einstellungen.js';

let hlsLaden = null;
const hlsJs = () => hlsLaden ??= new Promise((ok, fehl) => { const s = document.createElement('script'); s.src = 'hls.min.js'; s.onload = () => ok(window.Hls); s.onerror = fehl; document.head.append(s); });

function kannDirekt(ms) {
	const v = ms.MediaStreams?.find(s => s.Type === 'Video'), a = ms.MediaStreams?.find(s => s.Type === 'Audio' && s.Index === ms.DefaultAudioStreamIndex) || ms.MediaStreams?.find(s => s.Type === 'Audio');
	const c = (ms.Container || '').toLowerCase();
	if (!['mp4', 'm4v', 'mov', 'webm'].some(x => c.includes(x))) return false;
	const vc = (v?.Codec || '').toLowerCase(), ac = (a?.Codec || '').toLowerCase();
	const video = document.createElement('video');
	const vOk = vc === 'h264' ? true : vc === 'hevc' ? !!video.canPlayType('video/mp4; codecs="hvc1.1.6.L93.B0"') : vc === 'vp9' || vc === 'av1';
	return vOk && (!a || ['aac', 'mp3', 'opus', 'flac'].includes(ac));
}

export async function spielen(id, startMs = 0) {
	document.querySelector('.spieler')?.remove();
	const video = h('video', { autoplay: true, playsinline: true });
	const titel = h('div'); const skip = h('div', { class: 'skip' }); const uhr = h('div', { class: 'glasuhr' });
	const spurenKnopf = h('button', { title: 'Ton und Untertitel', onclick: () => spurenZeigen() }, '💬');
	const wurzel = h('div', { class: 'spieler' }, video, h('div', { class: 'oben' }, h('button', { title: 'Zurück', onclick: () => schliessen() }, '←'), titel, h('div', { style: 'flex:1' }), spurenKnopf), skip, E.an('uhr_film') ? uhr : null);
	document.body.append(wurzel);
	let item, ms, sitzungId, methode, segmente = [], naechste = null, hls = null, abspannWeg = false, countdown = null, gemeldet = false, audioIndex = null, untertitelIndex = -1;
	let ruhig; const wach = () => { wurzel.classList.remove('ruhig'); clearTimeout(ruhig); ruhig = setTimeout(() => wurzel.classList.add('ruhig'), 3000); };
	wurzel.addEventListener('mousemove', wach); wach();
	const takt = setInterval(() => { const d = new Date(); uhr.textContent = d.toLocaleTimeString('de-CH', { hour: '2-digit', minute: '2-digit' }); }, 1000);
	const tasten = (e) => {
		if (e.key === 'Escape' || e.key === 'Backspace') schliessen();
		else if (e.key === ' ' || e.key === 'k') { video.paused ? video.play() : video.pause(); e.preventDefault(); }
		else if (e.key === 'ArrowLeft') video.currentTime -= 10; else if (e.key === 'ArrowRight') video.currentTime += 30;
		else if (e.key === 'f') document.fullscreenElement ? document.exitFullscreen() : wurzel.requestFullscreen();
		wach();
	};
	document.addEventListener('keydown', tasten);
	video.controls = true;

	const melden = (art) => {
		if (!item) return;
		const body = { ItemId: item.Id, MediaSourceId: ms?.Id, PlaySessionId: sitzungId, PositionTicks: Math.floor(video.currentTime * 1e7), IsPaused: video.paused, CanSeek: true, PlayMethod: methode, AudioStreamIndex: audioIndex, SubtitleStreamIndex: untertitelIndex };
		return api(`/Sessions/Playing${art}`, { methode: 'POST', body }).catch(() => {});
	};
	const fortschrittTakt = setInterval(() => { if (gemeldet && !video.paused) melden('/Progress'); }, 10000);
	video.addEventListener('pause', () => gemeldet && melden('/Progress')); video.addEventListener('play', () => gemeldet && melden('/Progress'));

	async function laden(itemId, start) {
		if (gemeldet) { await melden('/Stopped'); gemeldet = false; }
		hls?.destroy(); hls = null; abspannWeg = false; countdown = null; naechste = null; segmente = [];
		item = await api(`/Items/${itemId}`, { params: { userId: sitzung.benutzer.Id } });
		titel.replaceChildren(h('b', {}, item.Type === 'Episode' ? item.SeriesName : item.Name), item.Type === 'Episode' ? h('small', {}, `S${item.ParentIndexNumber}:F${item.IndexNumber} · ${item.Name}`) : null);
		const live = item.Type === 'TvChannel';
		const pi = await api(`/Items/${itemId}/PlaybackInfo`, { methode: 'POST', params: { userId: sitzung.benutzer.Id }, body: { UserId: sitzung.benutzer.Id, AutoOpenLiveStream: live, EnableDirectPlay: true, EnableDirectStream: true, EnableTranscoding: true } });
		ms = pi.MediaSources[0]; sitzungId = pi.PlaySessionId;
		// Tonspur: von Hand gewählte Sprache dieser Serie, sonst Vorschlag des Servers
		const gemerkt = item.SeriesId && localStorage.getItem('seliku-ton-' + item.SeriesId);
		const passend = gemerkt && ms.MediaStreams.find(s => s.Type === 'Audio' && s.Language === gemerkt);
		if (audioIndex === null || !passend) audioIndex = passend ? passend.Index : ms.DefaultAudioStreamIndex;
		const direkt = !live && kannDirekt(ms) && audioIndex === ms.DefaultAudioStreamIndex;
		methode = direkt ? 'DirectPlay' : 'Transcode';
		const tok = { ApiKey: sitzung.token };
		video.querySelectorAll('track').forEach(t => t.remove());
		if (direkt) video.src = `${sitzung.server}/Videos/${item.Id}/stream${q({ static: true, mediaSourceId: ms.Id, playSessionId: sitzungId, ...tok })}`;
		else {
			const url = live && ms.TranscodingUrl ? sitzung.server + ms.TranscodingUrl : `${sitzung.server}/Videos/${item.Id}/master.m3u8${q({ mediaSourceId: ms.Id, playSessionId: sitzungId, deviceId: sitzung.geraet, VideoCodec: 'h264', AudioCodec: 'aac', TranscodingMaxAudioChannels: 2, SegmentContainer: 'ts', MaxStreamingBitrate: 40000000, AudioStreamIndex: audioIndex, BreakOnNonKeyFrames: true, ...tok })}`;
			// HLS braucht die Anmeldung im Kopf (der Server nimmt sie nicht aus der Adresse) → hls.js mit Kopfzeile
			const Hls = await hlsJs().catch(() => null);
			if (Hls?.isSupported()) {
				hls = new Hls({ startPosition: start / 1000, xhrSetup: (x) => x.setRequestHeader('Authorization', `MediaBrowser Token="${sitzung.token}"`) });
				hls.loadSource(url); hls.attachMedia(video);
			} else video.src = url;
		}
		// Untertitel als WebVTT vom Server (Standard-Untertitel eingeschaltet)
		untertitelIndex = ms.DefaultSubtitleStreamIndex ?? -1;
		for (const s of ms.MediaStreams.filter(s => s.Type === 'Subtitle' && (s.IsTextSubtitleStream || ['subrip', 'srt', 'ass', 'ssa', 'webvtt', 'vtt', 'mov_text'].includes((s.Codec || '').toLowerCase())))) {
			const t = h('track', { kind: 'subtitles', label: s.DisplayTitle || s.Language || 'Untertitel', srclang: s.Language || 'und' });
			t.dataset.index = s.Index; video.append(t);
			// Untertitel mit Anmeldung im Kopf holen und als Blob einhängen
			fetch(`${sitzung.server}/Videos/${item.Id}/${ms.Id}/Subtitles/${s.Index}/0/Stream.vtt`, { headers: { Authorization: `MediaBrowser Token="${sitzung.token}"` } })
				.then(r => r.ok ? r.blob() : null).then(b => { if (b) { t.src = URL.createObjectURL(b); const n = [...video.querySelectorAll('track')].indexOf(t); if (video.textTracks[n]) video.textTracks[n].mode = s.Index === untertitelIndex ? 'showing' : 'disabled'; } }).catch(() => {});
		}
		video.addEventListener('loadedmetadata', () => {
			if (start > 0 && !hls) video.currentTime = start / 1000;
			[...video.textTracks].forEach((t, n) => { t.mode = +video.querySelectorAll('track')[n]?.dataset.index === untertitelIndex ? 'showing' : 'disabled'; });
		}, { once: true });
		video.play().catch(() => {});
		await melden(''); gemeldet = true;
		api(`/MediaSegments/${item.Id}`).then(r => segmente = r.Items || []).catch(() => {});
		if (item.Type === 'Episode') api(`/Shows/${item.SeriesId}/Episodes`, { params: { userId: sitzung.benutzer.Id, startItemId: item.Id, limit: 2 } }).then(r => naechste = (r.Items || []).find(x => x.Id !== item.Id) || null).catch(() => {});
	}

	// Vorspann/Rückblick: Knopf (wie Jellyfins Vorgabe «fragen»); Abspann: nächste Folge nach 10 s
	const knopfTakt = setInterval(() => {
		const p = video.currentTime * 1000, d = video.duration * 1000 || 0;
		const s = segmente.find(x => p >= x.StartTicks / 1e4 && p < x.EndTicks / 1e4 - 1000);
		const imAbspann = s?.Type === 'Outro' || (d > 0 && !segmente.some(x => x.Type === 'Outro') && d - p < 20000);
		const nf = E.wert('naechste_folge', 'true');
		skip.replaceChildren();
		if (imAbspann && naechste && !abspannWeg && nf !== 'false') {
			if (countdown === null) countdown = nf === 'true' ? 10 : -1;
			else if (countdown > 0) countdown -= 0.5;
			if (countdown === 0 || (countdown > 0 && countdown < 0.5)) { const n = naechste; laden(n.Id, 0); return; }
			skip.append(h('button', { class: 'pille', onclick: () => { abspannWeg = true; } }, 'Abspann ansehen'),
				h('button', { class: 'pille haupt', onclick: () => laden(naechste.Id, 0) }, countdown > 0 ? `Nächste Folge in ${Math.ceil(countdown)}` : 'Nächste Folge'));
			return;
		}
		if (!imAbspann) countdown = null;
		const text = { Intro: 'Vorspann überspringen', Recap: 'Rückblick überspringen', Preview: 'Vorschau überspringen', Commercial: 'Werbung überspringen', Outro: naechste ? null : 'Abspann überspringen' }[s?.Type];
		if (text) skip.append(h('button', { class: 'pille haupt', onclick: () => { video.currentTime = s.Type === 'Outro' ? video.duration : s.EndTicks / 1e7; } }, text));
	}, 500);
	video.addEventListener('ended', () => { if (naechste && E.wert('naechste_folge', 'true') === 'true') laden(naechste.Id, 0); else schliessen(); });

	function spurenZeigen() {
		const alt = wurzel.querySelector('.spuren'); if (alt) { alt.remove(); return; }
		const ton = ms.MediaStreams.filter(s => s.Type === 'Audio'), ut = [...video.querySelectorAll('track')];
		const box = h('div', { class: 'spuren' }, h('div', {}, 'Ton'),
			ton.map(s => h('button', { class: s.Index === audioIndex ? 'an' : '', onclick: () => {
				audioIndex = s.Index; if (item.SeriesId && s.Language) localStorage.setItem('seliku-ton-' + item.SeriesId, s.Language);
				box.remove(); laden(item.Id, video.currentTime * 1000);
			} }, s.DisplayTitle || s.Language)),
			h('div', {}, 'Untertitel'),
			h('button', { class: untertitelIndex === -1 ? 'an' : '', onclick: () => { untertitelIndex = -1; [...video.textTracks].forEach(t => t.mode = 'disabled'); box.remove(); } }, 'Aus'),
			ut.map((t, n) => h('button', { class: +t.dataset.index === untertitelIndex ? 'an' : '', onclick: () => {
				untertitelIndex = +t.dataset.index; [...video.textTracks].forEach((x, m) => x.mode = m === n ? 'showing' : 'disabled'); box.remove();
			} }, t.label)));
		wurzel.append(box);
	}
	async function schliessen() {
		clearInterval(takt); clearInterval(fortschrittTakt); clearInterval(knopfTakt);
		document.removeEventListener('keydown', tasten);
		if (gemeldet) await melden('/Stopped');
		if (methode === 'Transcode') api('/Videos/ActiveEncodings', { methode: 'DELETE', params: { deviceId: sitzung.geraet, playSessionId: sitzungId } }).catch(() => {});
		hls?.destroy(); video.src = ''; wurzel.remove();
		if (document.fullscreenElement) document.exitFullscreen();
		window.dispatchEvent(new Event('seliku-neu'));
	}
	try { await laden(id, startMs); } catch (e) { meldung('Wiedergabe nicht möglich'); schliessen(); }
}
