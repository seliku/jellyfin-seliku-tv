// Rahmen der Seliku-Weboberfläche: Seitenleiste wie am TV (gleiche Reihenfolge und Ausblendregeln), Anmeldung, Wege.
import { sitzung, wiederherstellen, anmelden, quickConnect, ssoAnbieter, ssoStarten } from './api.js';
import * as D from './daten.js';
import * as S from './seerr.js';
import * as E from './einstellungen.js';
import { h, laden } from './ui.js';
import * as A from './seiten1.js';
import * as B from './seiten2.js';

const ZEICHEN = '<svg viewBox="30 24 60 60"><defs><linearGradient id="v" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFB347"/><stop offset="1" stop-color="#FF6B4A"/></linearGradient></defs><path fill="url(#v)" d="M44,31.5Q38,28 38,35L38,73Q38,80 44,76.5L78.5,57.5Q84,54 78.5,50.5Z"/></svg>';
const SYMBOL = {
	start: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z', suche: 'M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
	entdecken: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm2.19 12.19L6 18l3.81-8.19L18 6l-3.81 8.19zM12 10.9a1.1 1.1 0 1 0 0 2.2 1.1 1.1 0 0 0 0-2.2z',
	movies: 'M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V4h-4z', tvshows: 'M21 3H3a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h5v2h8v-2h5a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zm0 14H3V5h18v12z',
	livetv: 'M21 6h-7.59l3.29-3.29L16 2l-4 4-4-4-.71.71L10.59 6H3a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h18a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2zm0 14H3V8h18v12zM9 10v8l7-4z',
	music: 'M12 3v10.55A4 4 0 1 0 14 17V7h4V3h-6z', books: 'M18 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2zM6 4h5v8l-2.5-1.5L6 12V4z',
	ordner: 'M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8l-2-2z', boxsets: 'M4 6H2v14a2 2 0 0 0 2 2h14v-2H4V6zm16-4H8a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2z',
	mein: 'M12 12a5 5 0 1 0 0-10 5 5 0 0 0 0 10zm0 2c-3.33 0-10 1.67-10 5v3h20v-3c0-3.33-6.67-5-10-5z', liste: 'M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09A5.99 5.99 0 0 1 16.5 3C19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z',
	einstellungen: 'M19.14 12.94a7.07 7.07 0 0 0 0-1.88l2.03-1.58-1.92-3.32-2.39.96a7.03 7.03 0 0 0-1.62-.94L14.88 3.6h-3.84l-.36 2.58c-.59.24-1.13.56-1.62.94l-2.39-.96-1.92 3.32 2.03 1.58a7.07 7.07 0 0 0 0 1.88l-2.03 1.58 1.92 3.32 2.39-.96c.49.38 1.03.7 1.62.94l.36 2.58h3.84l.36-2.58c.59-.24 1.13-.56 1.62-.94l2.39.96 1.92-3.32-2.03-1.58zM12 15.6a3.6 3.6 0 1 1 0-7.2 3.6 3.6 0 0 1 0 7.2z',
};
const svg = (k) => `<svg viewBox="0 0 24 24"><path d="${SYMBOL[k] || SYMBOL.ordner}"/></svg>`;

let leiste, main;
async function leisteBauen() {
	const bibs = await D.bibliotheken(); const aufn = await D.aufnahmeOrdner();
	const echteMusik = bibs.some(b => b.CollectionType === 'music' && !/podcast/i.test(b.Name));
	const sichtbar = bibs.filter(b => b.CollectionType !== 'playlists'
		&& !(echteMusik && ((b.CollectionType === 'music' && /podcast/i.test(b.Name)) || b.CollectionType === 'books'))   // Reiter unter Musik (S 08.11.: Leiste kurz)
		&& !(aufn.has(b.Id) && bibs.some(x => x.CollectionType === 'livetv')))                                          // Aufnahmen unter Live-TV (S 12.11.)
		.sort((a, b) => ({ movies: 0, tvshows: 1, livetv: 2, music: 3 }[a.CollectionType] ?? 4) - ({ movies: 0, tvshows: 1, livetv: 2, music: 3 }[b.CollectionType] ?? 4));
	const eintrag = (href, sym, titel, weg) => h('a', { href, class: weg ? 'weg' : '', html: svg(sym) + `<span>${titel}</span>` });
	const uhr = h('div', { class: 'uhr' });
	const tick = () => { const d = new Date(); uhr.textContent = d.toLocaleDateString('de-CH', { weekday: 'short' }).toUpperCase().replace('.', '') + '  ' + d.toLocaleTimeString('de-CH', { hour: '2-digit', minute: '2-digit' }); };
	tick(); setInterval(tick, 15000);
	leiste.replaceChildren(h('div', { class: 'marke', html: ZEICHEN + 'Seliku' }),
		eintrag('#/', 'start', 'Start'), eintrag('#/suche', 'suche', 'Suche'),
		S.seerr.verbunden ? eintrag('#/entdecken', 'entdecken', 'Entdecken') : null,
		...sichtbar.map(b => eintrag(B.bibZiel(b), b.CollectionType || 'ordner', b.Name, !['movies', 'tvshows'].includes(b.CollectionType))),
		h('div', { class: 'unten' }), eintrag('#/liste', 'liste', 'Meine Liste', true), eintrag(E.an('mein_verlauf') ? '#/mein' : '#/mein', 'mein', 'Mein Seliku'),
		eintrag('#/einstellungen', 'einstellungen', 'Einstellungen', true), uhr);
	markieren();
}
function markieren() {
	const w = location.hash || '#/';
	leiste?.querySelectorAll('a').forEach(a => a.classList.toggle('aktiv', a.getAttribute('href') === '#/' ? (w === '#/' || w === '#') : w.startsWith(a.getAttribute('href'))));
}

const WEGE = [
	[/^#?\/?$/, (el) => A.start(el)],
	[/^#\/suche$/, (el) => B.suche(el)],
	[/^#\/entdecken$/, (el) => B.entdecken(el)],
	[/^#\/bib\/([^/]+)(?:\/(\w+))?$/, (el, m) => A.bibliothek(el, m[1], m[2])],
	[/^#\/raster\/([^/]+)\/(.+)$/, (el, m) => A.rasterSeite(el, m[1], decodeURIComponent(m[2]))],
	[/^#\/detail\/([^/]+)$/, (el, m) => A.detail(el, m[1])],
	[/^#\/seerr\/(movie|tv)\/(\d+)$/, (el, m) => B.seerrDetail(el, m[1], +m[2])],
	[/^#\/seerrliste\/([\w-]+)\/(\d+)\/(.*)$/, (el, m) => B.seerrListe(el, m[1], +m[2], m[3])],
	[/^#\/livetv$/, (el) => B.livetv(el)],
	[/^#\/programm$/, (el) => import('./epg.js').then(m => m.programm(el))],
	[/^#\/musik(?:\/([\w-]+))?$/, (el, m) => B.musik(el, m[1])],
	[/^#\/buch\/([^/]+)$/, (el, m) => B.buch(el, m[1])],
	[/^#\/podcast\/([^/]+)$/, (el, m) => B.podcast(el, m[1])],
	[/^#\/album\/([^/]+)$/, (el, m) => B.album(el, m[1])],
	[/^#\/interpret\/([^/]+)$/, (el, m) => B.interpret(el, m[1])],
	[/^#\/ordner\/([^/]+)$/, (el, m) => B.ordner(el, m[1])],
	[/^#\/mein$/, (el) => B.mein(el)],
	[/^#\/liste$/, (el) => B.liste(el)],
	[/^#\/bibliotheken$/, (el) => B.bibliotheken(el)],
	[/^#\/einstellungen$/, (el) => B.einstellungen(el)],
];
const rollen = {};
let aktuell = null;
async function zeigen() {
	if (aktuell) rollen[aktuell] = scrollY;
	A.titelMusikStopp();
	const w = location.hash || '#/'; aktuell = w;
	const el = h('div'); main.replaceChildren(el); markieren();
	for (const [re, f] of WEGE) {
		const m = w.match(re);
		if (m) {
			try { const p = f(el, m); scrollTo(0, 0); await p; } catch (e) { console.error(e); el.append(h('div', { class: 'hinweis' }, 'Das ging leider schief: ' + e.message)); }
			if (rollen[w] && aktuell === w) scrollTo(0, rollen[w]);
			return;
		}
	}
	el.append(h('div', { class: 'hinweis' }, 'Seite nicht gefunden.'));
}

/**
 * Hinweis auf die Handy-App: Android-Browser bekommen «Installieren» (lädt die App vom eigenen Server, nur wenn sie
 * dort liegt), iPhones die Anleitung «Zum Home-Bildschirm». Mit ✕ dauerhaft weg (pro Browser).
 */
async function appHinweis() {
	const WEG = 'seliku-app-hinweis-weg';
	try { if (localStorage.getItem(WEG)) return; } catch {}
	const ua = navigator.userAgent;
	const android = /Android/i.test(ua) && !/\bwv\b/.test(ua);
	const ios = /iPhone|iPad|iPod/.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
	const alsApp = matchMedia('(display-mode: standalone)').matches || navigator.standalone;
	if (!android && !(ios && !alsApp)) return;
	if (android) { try { const r = await fetch('handy-version.json', { cache: 'no-store' }); if (!r.ok) return; } catch { return; } }
	const text = h('div', { class: 'text' });
	const leiste = h('div', { class: 'app-hinweis' }, h('div', { class: 'zeichen', html: ZEICHEN }), text,
		h('button', { class: 'zu', title: 'Nicht mehr anzeigen', onclick: () => { try { localStorage.setItem(WEG, '1'); } catch {} leiste.remove(); } }, '✕'));
	if (android) {
		text.append(h('b', {}, 'Seliku gibt es als App'), h('span', {}, 'Fürs Android-Handy, mit Updates.'));
		leiste.insertBefore(h('a', { class: 'pille haupt', href: 'seliku-handy.bin', download: 'seliku-handy.apk', onclick: () => {
			text.replaceChildren(h('b', {}, 'Wird geladen …'), h('span', {}, 'Danach die Datei öffnen und «Installieren» tippen. Beim ersten Mal fragt Android, ob dein Browser Apps installieren darf: erlauben.'));
		} }, 'Installieren'), leiste.lastChild);
	} else {
		text.append(h('b', {}, 'Seliku als App'), h('span', {}, 'Unten auf «Teilen» tippen, dann «Zum Home-Bildschirm».'));
	}
	document.body.prepend(leiste);
}

function anmeldungZeigen() {
	const name = h('input', { type: 'text', placeholder: 'Benutzer', autocomplete: 'username' });
	const pw = h('input', { type: 'password', placeholder: 'Passwort', autocomplete: 'current-password' });
	const fehler = h('div', { class: 'fehler' }), code = h('div', { class: 'code' });
	const form = h('form', { onsubmit: async (e) => { e.preventDefault(); fehler.textContent = ''; try { await anmelden(name.value.trim(), pw.value); location.reload(); } catch { fehler.textContent = 'Benutzer oder Passwort falsch'; } } },
		h('div', { html: ZEICHEN }), h('h1', { style: 'margin:0' }, 'Jellyfin Seliku'), name, pw, fehler, h('button', { class: 'pille haupt', type: 'submit' }, 'Anmelden'),
		h('button', { class: 'pille', type: 'button', onclick: async () => { try { await quickConnect(c => code.replaceChildren(h('div', { style: 'font-size:14px;color:var(--leise)' }, 'In einer angemeldeten Jellyfin-App unter «Quick Connect» eingeben:'), c)); location.reload(); } catch { fehler.textContent = 'Quick Connect nicht möglich'; } } }, 'Mit Quick Connect anmelden'), code);
	document.body.replaceChildren(h('div', { class: 'anmeldung' }, form));
	appHinweis();
	// SSO-Knopf, wenn der Server einen anbietet (SSO-Plugin)
	ssoAnbieter().then(s => { if (s) form.insertBefore(h('button', { class: 'pille sso', type: 'button', onclick: () => ssoStarten(s.pfad) }, s.text), code); });
}

async function starten() {
	if (!(await wiederherstellen())) return anmeldungZeigen();
	leiste = h('nav', { class: 'leiste' }); main = h('main');
	document.body.replaceChildren(leiste, main);
	appHinweis();
	E.holen().catch(() => {});
	await Promise.race([S.pruefen(), new Promise(r => setTimeout(r, 2500))]);
	leisteBauen().catch(e => console.error(e));
	window.addEventListener('hashchange', zeigen);
	window.addEventListener('seliku-neu', () => { if (!document.querySelector('.spieler')) zeigen(); });
	zeigen();
}
starten();
