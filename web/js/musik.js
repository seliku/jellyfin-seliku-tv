// Musik und Radio im Browser: Warteschlange mit HTML-Audio, Mini-Player unten rechts, Fortschritt an Jellyfin,
// Endlos-Mix wie am TV (am Ende der Warteschlange passende Titel anhängen).
import { api, sitzung, q, bild, gid } from './api.js';
import { h } from './ui.js';
import * as E from './einstellungen.js';
import * as H from './hoeren.js';

const audio = new Audio();
export const zustand = { liste: [], index: -1, radio: null, hoer: null }; // hoer: 'b:<Buch>' | 'p' | null
let mini = null;

export async function titelVon(i) {
	const p = { userId: sitzung.benutzer.Id, fields: 'MediaSources', enableUserData: true };
	if (i.Type === 'Audio') return [i];
	if (i.Type === 'MusicAlbum') return (await api('/Items', { params: { ...p, parentId: i.Id, includeItemTypes: 'Audio', sortBy: 'ParentIndexNumber,IndexNumber,SortName' } })).Items;
	if (i.Type === 'Playlist') return (await api(`/Playlists/${i.Id}/Items`, { params: p })).Items;
	if (i.Type === 'MusicArtist') return (await api('/Items', { params: { ...p, artistIds: i.Id, recursive: true, includeItemTypes: 'Audio', sortBy: 'Album,ParentIndexNumber,IndexNumber', limit: 500 } })).Items;
	return [];
}
function melden(art) {
	const t = zustand.liste[zustand.index]; if (!t || zustand.radio) return;
	api(`/Sessions/Playing${art}`, { methode: 'POST', body: { ItemId: t.Id, PositionTicks: Math.floor(audio.currentTime * 1e7), IsPaused: audio.paused, CanSeek: true, PlayMethod: 'DirectPlay' } }).catch(() => {});
}
function spieleIndex(n, abMs = 0) {
	if (zustand.index >= 0) melden('/Stopped');
	zustand.index = n; zustand.radio = null;
	const t = zustand.liste[n]; if (!t) { zeigen(); return; }
	audio.src = `${sitzung.server}/Audio/${t.Id}/stream${q({ static: true, ApiKey: sitzung.token })}`;
	if (abMs) audio.addEventListener('loadedmetadata', () => { audio.currentTime = abMs / 1000; }, { once: true });
	audio.play().catch(() => {}); melden(''); zeigen();
	if ('mediaSession' in navigator) navigator.mediaSession.metadata = new MediaMetadata({ title: t.Name, artist: (t.Artists || []).join(', '), album: t.Album, artwork: [{ src: bild.poster(t, 512) || '' }] });
}
export function musikSpielen(liste, ab = 0, zufall = false) {
	zustand.hoer = null; audio.playbackRate = 1;
	zustand.liste = zufall ? [...liste].sort(() => Math.random() - 0.5) : [...liste];
	spieleIndex(zufall ? 0 : ab);
}
/** Hörbuch ab gemerkter Stelle (minus Zurückspulen nach Pausenlänge), [ab] = ab diesem Kapitel. */
export async function buchSpielen(buch, ab) {
	await H.laden(); const d = await H.buchDateien(buch.Id); if (!d.length) return;
	const B = gid(buch.Id); const st = H.wert('b:' + B)?.split('|'); let n = 0, pos = 0;
	if (ab !== undefined) n = ab; else if (st?.length >= 3) { n = Math.max(0, d.findIndex(x => gid(x.Id) === st[0])); pos = Math.max(0, +st[1] - H.rueckspulen(+st[2])); }
	H.setzen('f:' + B, null);
	zustand.liste = d; zustand.hoer = 'b:' + B; audio.playbackRate = +(H.wert('t:' + B) || 1); spieleIndex(n, pos);
}
/** Podcast-Folge an der gemerkten Stelle, danach die nächsten ungehörten desselben Podcasts. */
export async function folgeSpielen(f) {
	await H.laden();
	const alle = f.AlbumId ? (await api('/Items', { params: { parentId: f.AlbumId, recursive: true, includeItemTypes: 'Audio', sortBy: 'PremiereDate,DateCreated,SortName', sortOrder: 'Ascending', userId: sitzung.benutzer.Id, enableUserData: true } })).Items : [];
	const k = alle.findIndex(x => x.Id === f.Id); const danach = k >= 0 ? alle.slice(k + 1).filter(x => !H.gehoert(x)) : [];
	const st = H.wert('p:' + gid(f.Id))?.split('|'); const pos = st ? Math.max(0, +st[0] - H.rueckspulen(+st[1] || 0)) : 0;
	zustand.liste = [f, ...danach]; zustand.hoer = 'p'; audio.playbackRate = +(H.wert('t:' + gid(f.AlbumId)) || 1); spieleIndex(0, pos);
}
// Stelle alle 5 s merken, alle 30 s hochladen, bei Pause sofort; Podcast ab 95 % bzw. 45 s vor Schluss gehört
let takt = 0;
function hoerMerken() {
	const t = zustand.liste[zustand.index]; if (!t || !zustand.hoer) return;
	const pos = Math.floor(audio.currentTime * 1000), d = (audio.duration || 0) * 1000;
	const T = gid(t.Id);
	if (zustand.hoer.startsWith('b:')) H.setzen(zustand.hoer, `${T}|${pos}|${Date.now()}`);
	else if (d > 0 && (pos >= d * 0.95 || d - pos < 45000)) { if (H.wert('p:' + T)) { H.setzen('p:' + T, null); api(`/UserPlayedItems/${t.Id}`, { methode: 'POST', params: { userId: sitzung.benutzer.Id } }).catch(() => {}); } }
	else H.setzen('p:' + T, `${pos}|${Date.now()}`);
}
setInterval(() => { if (audio.paused || !zustand.hoer) return; takt++; if (takt % 5 === 0) hoerMerken(); if (takt % 30 === 0) H.hochladen(); }, 1000);
audio.addEventListener('pause', () => { if (zustand.hoer) { hoerMerken(); H.hochladen(); } });
export function tempoWeiter() {
	const st = [0.8, 1, 1.1, 1.25, 1.5, 1.75, 2]; const n = st[(st.findIndex(x => x >= audio.playbackRate - 0.01) + 1) % st.length];
	audio.playbackRate = n; const t = zustand.liste[zustand.index];
	const k = zustand.hoer?.startsWith('b:') ? zustand.hoer.slice(2) : (t?.AlbumId && gid(t.AlbumId)); if (k) H.setzen('t:' + k, n === 1 ? null : String(n)); zeigen();
}

export function radioSpielen(s) {
	zustand.hoer = null;
	if (zustand.index >= 0) melden('/Stopped');
	zustand.radio = s; zustand.index = -1; audio.src = s.url; audio.play().catch(() => {}); zeigen();
}
audio.addEventListener('ended', async () => {
	if (zustand.index + 1 < zustand.liste.length) return spieleIndex(zustand.index + 1);
	if (zustand.hoer?.startsWith('b:')) { H.setzen(zustand.hoer, null); H.setzen('f:' + zustand.hoer.slice(2), String(Date.now())); H.hochladen(); }
	if (zustand.hoer) { melden('/Stopped'); zustand.index = -1; zeigen(); return; }
	const t = zustand.liste[zustand.index];
	if (t && E.wert('endlos_musik', 'true') === 'true') {
		const da = new Set(zustand.liste.map(x => x.Id));
		const neu = ((await api(`/Items/${t.Id}/InstantMix`, { params: { userId: sitzung.benutzer.Id, limit: 50 } }).catch(() => ({}))).Items || []).filter(x => x.Type === 'Audio' && !da.has(x.Id)).slice(0, 25);
		if (neu.length) { zustand.liste.push(...neu); return spieleIndex(zustand.index + 1); }
	}
	melden('/Stopped'); zustand.index = -1; zeigen();
});
setInterval(() => { if (!audio.paused && !zustand.radio) melden('/Progress'); }, 10000);
audio.addEventListener('play', zeigen); audio.addEventListener('pause', zeigen);

function zeigen() {
	const t = zustand.radio ? { Name: zustand.radio.name, Artists: ['Radio'], bild: zustand.radio.logo } : zustand.liste[zustand.index];
	if (!t) { mini?.remove(); mini = null; return; }
	const neu = h('div', { class: 'mini' }, h('img', { src: t.bild || bild.poster(t, 120) || '', alt: '' }),
		h('div', { class: 't' }, h('div', {}, t.Name), h('small', {}, (t.Artists || []).join(', '))),
		zustand.radio ? null : h('button', { title: 'Zurück', onclick: () => audio.currentTime > 3 ? (audio.currentTime = 0) : spieleIndex(Math.max(0, zustand.index - 1)) }, '⏮'),
		zustand.hoer ? h('button', { title: 'Zurück', onclick: () => audio.currentTime -= zustand.hoer.startsWith('b:') ? 30 : 10 }, '↺') : null,
		h('button', { title: 'Pause', onclick: () => audio.paused ? audio.play() : audio.pause() }, audio.paused ? '▶' : '❚❚'),
		zustand.hoer ? h('button', { title: '+30 s', onclick: () => audio.currentTime += 30 }, '↻') : null,
		zustand.hoer ? h('button', { title: 'Tempo', style: 'font-size:13px;width:auto;padding:0 6px', onclick: tempoWeiter }, `${audio.playbackRate}×`) : null,
		zustand.radio ? h('button', { title: 'Stopp', onclick: () => { audio.pause(); audio.src = ''; zustand.radio = null; zeigen(); } }, '■')
			: h('button', { title: 'Weiter', onclick: () => spieleIndex(zustand.index + 1) }, '⏭'));
	if (mini) mini.replaceWith(neu); else document.body.append(neu);
	mini = neu;
}
export const laeuft = () => !!(zustand.radio || zustand.liste[zustand.index]);
export const pausieren = () => audio.pause();
