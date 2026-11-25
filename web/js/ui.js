// Bausteine der Weboberfläche: Kacheln, Reihen, Banner, Titelmenü – wie am Fernseher.
import { bild, sitzung } from './api.js';
import * as D from './daten.js';
import * as E from './einstellungen.js';
import { ABZEICHEN } from './seerr.js';

export function h(tag, attr = {}, ...kinder) {
	const e = document.createElement(tag);
	for (const [k, v] of Object.entries(attr || {})) {
		if (v === undefined || v === null || v === false) continue;
		if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
		else if (k === 'class') e.className = v;
		else if (k === 'html') e.innerHTML = v;
		else if (k === 'style') e.style.cssText = v;
		else e.setAttribute(k, v === true ? '' : v);
	}
	for (const k of kinder.flat(9)) if (k !== null && k !== undefined && k !== false) e.append(k instanceof Node ? k : document.createTextNode(String(k)));
	return e;
}
export const img = (src, cls) => src ? h('img', { src, class: cls, loading: 'lazy', alt: '', onerror: (e) => e.target.remove() }) : null;
export function meldung(t) { const m = h('div', { class: 'meldung' }, t); document.body.append(m); setTimeout(() => m.remove(), 2800); }
export const laden = () => h('div', { class: 'laden' });
export const datenstand = { stand: 0, neu() { this.stand++; window.dispatchEvent(new Event('seliku-neu')); } };

export function laufzeit(t) { const m = Math.floor((t || 0) / 6e8); return m <= 0 ? null : m >= 60 ? `${Math.floor(m / 60)} Std. ${m % 60} Min.` : `${m} Min.`; }
export function zeit(ms) { const s = Math.floor(ms / 1000); const z = (n) => String(n).padStart(2, '0'); return s >= 3600 ? `${Math.floor(s / 3600)}:${z(Math.floor(s / 60) % 60)}:${z(s % 60)}` : `${Math.floor(s / 60)}:${z(s % 60)}`; }
export function hauptzeile(i) { return (i.Type === 'Episode' || i.Type === 'Season') ? (i.SeriesName || i.Name) : i.Name; }
export function unterzeile(i) {
	switch (i.Type) {
		case 'Episode': return `S${i.ParentIndexNumber ?? 0} · F${i.IndexNumber ?? 0}  ${i.Name || ''}`;
		case 'MusicAlbum': return [i.AlbumArtist, i.ProductionYear].filter(Boolean).join(' · ');
		case 'Audio': return (i.Artists || []).join(', ');
		case 'Series': return [i.ProductionYear, i.ChildCount ? (i.ChildCount === 1 ? '1 Staffel' : `${i.ChildCount} Staffeln`) : null].filter(Boolean).join(' · ');
		case 'TvChannel': return i.CurrentProgram?.Name || '';
		default: return i.ProductionYear || '';
	}
}
export function metazeile(i, mitGenres = true) {
	const r = i.UserData?.Rating ? Math.round(i.UserData.Rating / 2) : 0;
	return [r >= 1 && r <= 5 && E.an('eigene_wertung') ? 'Du: ' + '★'.repeat(r) + '☆'.repeat(5 - r) : null, i.ProductionYear, i.OfficialRating,
		i.Type !== 'Series' ? laufzeit(i.RunTimeTicks) : null, i.CriticRating && E.an('tomaten') ? `🍅 ${i.CriticRating} %` : null,
		i.CommunityRating ? `TMDb ${i.CommunityRating.toFixed(1)}` : null, mitGenres ? (i.Genres || []).slice(0, 3).join(', ') : null].filter(Boolean).join('   ·   ');
}
function fortschritt(i) {
	const p = i.UserData?.PlaybackPositionTicks || 0, d = i.RunTimeTicks || 0;
	return (p > 0 && d > 0 && !i.UserData?.Played) ? h('div', { class: 'fortschritt' }, h('i', { style: `width:${Math.max(2, p / d * 100)}%` })) : null;
}
function abzeichen(i) {
	const u = i.UserData?.UnplayedItemCount || 0;
	if ((i.Type === 'Series' || i.Type === 'Season') && u > 0 && !i.UserData?.Played) return h('span', { class: 'abz' }, u);
	if (i.UserData?.Played) return h('span', { class: 'abz haken' }, '✓');
	return null;
}

/** Kachel: art = poster | quer | quadrat | rund. Rechtsklick oder ⋮ öffnet das Titelmenü. */
export function kachel(i, art = 'poster', klick, { z1 = hauptzeile(i), z2 = unterzeile(i), bildUrl } = {}) {
	const src = bildUrl ?? (art === 'quer' ? bild.quer(i) : bild.poster(i, art === 'poster' ? 300 : 400));
	const k = h('div', { class: `kachel ${art === 'poster' ? '' : art}`, title: i.Name, onclick: () => klick(i), oncontextmenu: (e) => { e.preventDefault(); titelMenue(i); } },
		h('div', { class: 'bild' }, img(src), abzeichen(i), fortschritt(i)),
		h('button', { class: 'menue', title: 'Mehr', onclick: (e) => { e.stopPropagation(); titelMenue(i); } }, '⋮'),
		z1 ? h('div', { class: 'z1' }, z1) : null, z2 ? h('div', { class: 'z2' }, z2) : null);
	return k;
}
export function seerrKachel(t, klick, anfragenZeichen = false) {
	const z = t.zustand;
	const abz = anfragenZeichen && z === 'ANFRAGEN' ? h('span', { class: 'abz seerr' }, '+ Anfragen')
		: ABZEICHEN[z] ? h('span', { class: `abz seerr ${z === 'GLEICH' ? 'gruen' : z === 'ANSEHEN' ? '' : 'dunkel'}` }, ABZEICHEN[z]) : null;
	return h('div', { class: 'kachel', title: t.titel, onclick: () => klick(t) }, h('div', { class: 'bild' }, img(t.poster), abz),
		h('div', { class: 'z1' }, t.titel), h('div', { class: 'z2' }, [t.jahr, t.typ === 'movie' ? 'Film' : 'Serie'].filter(Boolean).join(' · ')));
}

/** Waagrechte Reihe mit Überschrift und Pfeilen; lädt bei Bedarf nach ([mehr] liefert weitere Elemente). */
export function reihe(titel, liste, render, { alle, mehr } = {}) {
	if (!liste?.length) return null;
	const band = h('div', { class: 'band' }, liste.map(render));
	if (mehr) {
		let laeuft = false;
		band.addEventListener('scroll', async () => {
			if (laeuft || band.scrollLeft + band.clientWidth < band.scrollWidth - 600) return;
			laeuft = true; const neu = await mehr(); neu.forEach(x => band.append(render(x))); laeuft = !neu.length;
		});
	}
	const schieb = (r) => band.scrollBy({ left: r * band.clientWidth * 0.8 });
	return h('section', { class: 'reihe' }, h('h2', {}, titel, alle ? h('a', { href: alle }, 'Alle ›') : null),
		h('button', { class: 'pfeil l', onclick: () => schieb(-1) }, '‹'), band, h('button', { class: 'pfeil r', onclick: () => schieb(1) }, '›'));
}

// ── Titelmenü (Rechtsklick/⋮), Punkte wie am TV ──
export function dialog(inhalt) {
	const s = h('div', { class: 'schleier', onclick: (e) => { if (e.target === s) s.remove(); } }, h('div', { class: 'dialog' }, inhalt));
	document.body.append(s); return () => s.remove();
}
export async function titelMenue(item) {
	const N = await import('./navigation.js');
	let i = item; try { i = await D.item(item.Id); } catch {}
	const punkte = []; const p = (t, a) => punkte.push(h('button', { onclick: () => { zu(); a(); } }, t));
	const video = ['Movie', 'Episode', 'Video', 'MusicVideo'].includes(i.Type), musik = ['Audio', 'MusicAlbum', 'Playlist', 'MusicArtist'].includes(i.Type);
	const pos = i.UserData?.PlaybackPositionTicks || 0;
	const fertig = () => datenstand.neu();
	if (i.SeriesId) p('›  Zur Serie', () => N.geh(`#/detail/${i.SeriesId}`));
	if (musik) p('▶  Abspielen', () => N.musik(i));
	if (video) p(pos > 0 && !i.UserData?.Played ? `▶  Fortsetzen ab ${zeit(pos / 1e4)}` : '▶  Abspielen', () => N.abspielen(i));
	if (video) p('📺  Auf anderem Gerät abspielen', () => geraetWahl(i));
	if (video && pos > 0) p('↺  Von vorn', () => N.abspielen(i, true));
	if (i.Type === 'Episode' || (i.Type === 'Movie' && pos > 0)) p('✕  Aus Weiterschauen entfernen', async () => { await D.ausWeiterschauen(i); fertig(); });
	if (i.Type === 'Series' || i.Type === 'Season') p('⤨  Zufällige Folge', () => N.serieAbspielen(i.SeriesId || i.Id, true));
	if (!musik) { const g = i.UserData?.Played; p(g ? '○  Als ungesehen markieren' : '✓  Als gesehen markieren', async () => { await D.gesehen(i.Id, !g); fertig(); }); }
	const f = i.UserData?.IsFavorite;
	p(f ? '☆  Aus Favoriten' : '★  Zu Favoriten', async () => { await D.favorit(i.Id, !f); if (!f) meldung('Zu Favoriten hinzugefügt – auf der Startseite unter «Deine Favoriten»'); fertig(); });
	if (E.an('eigene_wertung') && ['Movie', 'Series', 'Episode', 'Video'].includes(i.Type)) p('☆  Bewerten', () => sterne(i));
	if (['Movie', 'Series', 'Season', 'MusicAlbum', 'MusicArtist', 'BoxSet'].includes(i.Type)) p('ⓘ  Details', () => N.oeffnen(i));
	if (E.an('loeschen') && i.CanDelete && !['Folder', 'CollectionFolder', 'TvChannel'].includes(i.Type)) p('⌫  Löschen', () => loeschenFragen(i));
	const zu = dialog([h('h3', {}, hauptzeile(i)), i.Type === 'Episode' ? h('div', { class: 'u' }, unterzeile(i)) : null, punkte]);
}
export function sterne(i) {
	const zu = dialog([h('h3', {}, 'Wie war\'s?'), h('div', { class: 'u' }, hauptzeile(i)),
		[[5, 'Super'], [4, 'Gut'], [3, 'Okay'], [2, 'Mässig'], [1, 'Kacke']].map(([n, t]) => h('button', { onclick: async () => { zu(); await D.wertung(i.Id, n); datenstand.neu(); } }, '★'.repeat(n) + '☆'.repeat(5 - n) + '   ' + t)),
		h('button', { onclick: async () => { zu(); await D.wertung(i.Id, 0); datenstand.neu(); } }, '✕  Wertung entfernen')]);
}
function loeschenFragen(i) {
	const zu = dialog([h('h3', {}, `«${hauptzeile(i)}» löschen?`), h('div', { class: 'u' }, i.Type === 'Series' ? 'Alle Folgen werden vom Server gelöscht. Das lässt sich nicht rückgängig machen.' : 'Die Datei wird vom Server gelöscht. Das lässt sich nicht rückgängig machen.'),
		h('button', { onclick: () => zu() }, 'Abbrechen'),
		h('button', { style: 'color:#FF6B4A', onclick: async () => { zu(); try { await D.loeschen(i.Id); meldung('Gelöscht'); datenstand.neu(); } catch { meldung('Löschen fehlgeschlagen'); } } }, '⌫  Löschen')]);
}

/** Titel an ein anderes Gerät schicken (z. B. die Seliku-App am Fernseher), über Jellyfins Fernsteuerung. */
export async function geraetWahl(i) {
	const { api } = await import('./api.js');
	const liste = h('div', {}, laden());
	const zu = dialog([h('h3', {}, 'Abspielen auf …'), liste]);
	const s = ((await api('/Sessions', { params: { controllableByUserId: sitzung.benutzer.Id, activeWithinSeconds: 960 } }).catch(() => [])) || [])
		.filter(x => x.SupportsRemoteControl && x.DeviceId !== sitzung.geraet);
	liste.replaceChildren(...(s.length ? s.map(x => h('button', { onclick: async () => {
		zu(); const pos = i.UserData?.Played ? 0 : Math.max(0, (i.UserData?.PlaybackPositionTicks || 0) - 5e7);
		try { await api(`/Sessions/${x.Id}/Playing`, { methode: 'POST', params: { playCommand: 'PlayNow', itemIds: i.Id, startPositionTicks: pos } }); meldung(`Läuft auf ${x.DeviceName}`); } catch { meldung('Gerät reagiert nicht'); }
	} }, `${x.DeviceName}`, h('div', { style: 'font-size:12px;opacity:.7' }, [x.Client, x.NowPlayingItem ? 'spielt: ' + x.NowPlayingItem.Name : null].filter(Boolean).join(' · ')))) : [h('div', { class: 'u' }, 'Kein Gerät gefunden. Die Seliku-App am Fernseher muss geöffnet sein.')]));
}
