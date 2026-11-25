// Seiten: Suche, Entdecken (Seerr), Live-TV, Musik, Radio, Mein Seliku, Meine Liste, Ordner, Einstellungen.
import { api, bild, sitzung, abmelden, gid, klassischOeffnen } from './api.js';
import * as D from './daten.js';
import * as S from './seerr.js';
import * as E from './einstellungen.js';
import { h, img, kachel, seerrKachel, reihe, laden, metazeile, unterzeile, zeit, laufzeit, titelMenue, meldung } from './ui.js';
import { oeffnen, abspielen, geh } from './navigation.js';
import { musikSpielen, radioSpielen, titelVon, buchSpielen, folgeSpielen } from './musik.js';
import * as H from './hoeren.js';
import { sendungsBild } from './seiten1.js';

const kopf = (titel, zurueck = false) => h('div', { class: 'kopf' }, zurueck ? h('button', { class: 'pille', onclick: () => history.back() }, '←') : null, h('h1', {}, titel));
const seerrOeffnen = (t) => geh(`#/seerr/${t.typ}/${t.tmdb}`);
const merk = { suche: '' };

// ── Suche (ab 2 Zeichen, 450 ms nach dem letzten Tippen; Jellyfin und Seerr gleichzeitig) ──
export function suche(el) {
	const feld = h('input', { type: 'search', placeholder: 'Filme, Serien, Musik, Sender …', value: merk.suche });
	const erg = h('div'); let zeit_, nr = 0;
	el.append(h('div', { class: 'suchfeld' }, feld), erg);
	const los = async () => {
		const t = feld.value.trim(); merk.suche = feld.value; const n = ++nr;
		if (t.length < 2) { erg.replaceChildren(); return; }
		erg.replaceChildren(h('div', { class: 'hinweis' }, 'Suche läuft …'));
		const [j, s] = await Promise.all([D.sicher(D.suche(t)), S.seerr.verbunden ? D.sicher(S.suche(t).then(l => l.filter(x => x.zustand !== 'ANSEHEN'))) : []]);
		if (n !== nr) return;
		const g = (typ) => j.filter(i => i.Type === typ);
		const r = [reihe('Filme', g('Movie'), i => kachel(i, 'poster', oeffnen)), reihe('Serien', g('Series'), i => kachel(i, 'poster', oeffnen)),
			reihe('Noch nicht da – anfragen', s, t => seerrKachel(t, seerrOeffnen)), reihe('Folgen', g('Episode'), i => kachel(i, 'quer', abspielen)),
			reihe('Sender', g('TvChannel'), i => kachel(i, 'quer', abspielen, { bildUrl: sendungsBild(i) })), reihe('Interpreten', g('MusicArtist'), i => kachel(i, 'rund', oeffnen)),
			reihe('Alben', g('MusicAlbum'), i => kachel(i, 'quadrat', oeffnen)), reihe('Titel', g('Audio'), i => kachel(i, 'quadrat', oeffnen))].filter(Boolean);
		erg.replaceChildren(...(r.length ? r : [h('div', { class: 'hinweis' }, 'Nichts gefunden.')]));
	};
	feld.addEventListener('input', () => { clearTimeout(zeit_); zeit_ = setTimeout(los, 450); });
	setTimeout(() => feld.focus(), 50); if (merk.suche) los();
}

// ── Entdecken (zehn Reihen in fester Folge wie am TV) ──
function rubrikKachel(r) {
	const k = h('div', { class: 'kachel quer', onclick: () => geh(`#/seerrliste/${r.art}/${r.id}/${encodeURIComponent(r.name)}`) },
		h('div', { class: 'bild', style: 'display:grid;place-items:center' }, r.bild ? img(r.bild) : null, h('b', { style: 'position:absolute;font-size:18px;text-shadow:0 2px 8px #000' }, r.bild || !r.art.match(/studio|sender/) ? r.name : '')));
	if (r.art === 'studio' || r.art === 'sender') S.logo(r).then(l => { const b = k.querySelector('.bild'); if (l) b.prepend(h('img', { src: l, style: 'object-fit:contain;padding:22px' })); else b.querySelector('b').textContent = r.name; });
	return k;
}
export async function entdecken(el) {
	el.append(kopf('Entdecken'));
	if (!S.seerr.verbunden) { el.append(h('div', { class: 'hinweis' }, 'Seerr ist nicht verbunden.')); return; }
	const inhalt = h('div', {}, laden()); el.append(inhalt);
	const titelReihe = (titel, pfad, typ) => S.blatt(pfad, typ, 1).then(r => { let seite = 1; return reihe(titel, r.liste, t => seerrKachel(t, seerrOeffnen), { mehr: async () => (++seite <= Math.min(r.seiten, 5)) ? (await S.blatt(pfad, typ, seite)).liste : [] }); });
	try {
		const r = await Promise.all([titelReihe('Im Trend', '/discover/trending', null), titelReihe('Beliebte Filme', '/discover/movies', 'movie'),
			D.sicher(S.genreKacheln('movie')).then(l => reihe('Filmgenres', l, rubrikKachel)), D.sicher(titelReihe('Bald im Kino', '/discover/movies/upcoming', 'movie'), null),
			reihe('Studios', S.STUDIOS, rubrikKachel), titelReihe('Beliebte Serien', '/discover/tv', 'tv'),
			D.sicher(S.genreKacheln('tv')).then(l => reihe('Seriengenres', l, rubrikKachel)), D.sicher(titelReihe('Neue Serien demnächst', '/discover/tv/upcoming', 'tv'), null),
			reihe('Sender und Streamingdienste', S.SENDER, rubrikKachel),
			D.sicher(S.meineAnfragen()).then(l => reihe('Deine Anfragen', l.filter(t => t.zustand !== 'ANSEHEN'), t => seerrKachel(t, seerrOeffnen)))]);
		inhalt.replaceChildren(...r.filter(Boolean));
	} catch (e) { inhalt.replaceChildren(h('div', { class: 'hinweis' }, [401, 403].includes(e.code) ? 'Die Anmeldung bei Seerr ist abgelaufen.' : 'Seerr ist gerade nicht erreichbar.')); }
}
export async function seerrListe(el, art, id, titel) {
	el.append(kopf(decodeURIComponent(titel), true));
	const gitter = h('div', { class: 'raster' }), fuss = h('div', {}, laden()); el.append(gitter, fuss);
	if (art === 'person') { (await D.sicher(S.filmografie(id))).forEach(t => gitter.append(seerrKachel(t, seerrOeffnen))); fuss.replaceChildren(); return; }
	const [pfad, typ] = S.quelle(art, id); let seite = 0, seiten = 1, laeuft = false;
	const mehr = async () => { if (laeuft || seite >= Math.min(seiten, 25)) { fuss.replaceChildren(); return; } laeuft = true; const r = await D.sicher(S.blatt(pfad, typ, ++seite), { liste: [], seiten: 0 }); seiten = r.seiten; r.liste.forEach(t => gitter.append(seerrKachel(t, seerrOeffnen))); laeuft = false; };
	new IntersectionObserver(e => e[0].isIntersecting && mehr(), { rootMargin: '800px' }).observe(fuss); mehr();
}
export async function seerrDetail(el, typ, tmdb, erstes = true) {
	let d; try { d = await S.detail(typ, tmdb); } catch { el.append(h('div', { class: 'hinweis' }, 'Seerr ist gerade nicht erreichbar.')); return; }
	const t = d.titel, m = d.mehr;
	if (erstes && t.zustand === 'ANSEHEN') { const j = await jellyfinFinden(t); if (j) { history.replaceState(null, '', `#/detail/${j.Id}`); return geh(`#/detail/${j.Id}`); } }
	const meldungEl = h('div', { class: 'tagline', style: 'margin-top:8px' });
	const neu = () => { el.replaceChildren(); seerrDetail(el, typ, tmdb, false); };
	const kn = [];
	const z = t.zustand;
	if (z === 'ANSEHEN') kn.push(h('button', { class: 'pille haupt', onclick: async () => { const j = await jellyfinFinden(t); j ? geh(`#/detail/${j.Id}`) : (meldungEl.textContent = 'In Jellyfin noch nicht gefunden – die Bibliothek liest es gleich ein.'); } }, '▶  Ansehen'));
	else if (z === 'GLEICH') kn.push(h('button', { class: 'pille', onclick: neu }, '↓  Gleich verfügbar'));
	else if (z === 'NOCH_NICHT') kn.push(h('button', { class: 'pille', onclick: neu }, '…  Noch nicht verfügbar'));
	else if (z === 'WIRD_GESUCHT') kn.push(h('button', { class: 'pille', onclick: neu }, '⌕  Wird gesucht'));
	else if (z === 'ANGEFRAGT') kn.push(h('button', { class: 'pille', onclick: neu }, '⏳  Angefragt – wartet auf Freigabe'));
	else if (typ === 'tv' && m.ohneTvdb) kn.push(h('button', { class: 'pille', onclick: () => meldungEl.textContent = 'Diese Serie fehlt bei TheTVDB, deshalb kann Sonarr sie nicht laden. Eine Anfrage würde verworfen.' }, '–  Nicht anfragbar'));
	else kn.push(h('button', { class: 'pille haupt', onclick: async (e) => {
		e.target.textContent = 'Wird angefragt …'; e.target.disabled = true;
		try { await S.anfragen(t); neu(); } catch (f) { e.target.textContent = '+  Anfragen'; e.target.disabled = false;
			meldungEl.textContent = f.code === 202 ? 'Seerr hat die Anfrage nicht angenommen. Ein Admin kann den Eintrag in Seerr zurücksetzen («Daten löschen»).' : f.code === 403 ? 'Dafür fehlt in Seerr die Berechtigung.' : `Fehlgeschlagen: ${f.message}`; }
	} }, '+  Anfragen'));
	if (t.anfrageVonMir && ['ANGEFRAGT', 'WIRD_GESUCHT', 'NOCH_NICHT'].includes(z)) kn.push(h('button', { class: 'pille', onclick: async () => { await D.sicher(S.zuruecknehmen(t), null); neu(); } }, '✕  Anfrage zurücknehmen'));
	if (m.youtube) kn.push(h('button', { class: 'pille', onclick: () => trailerYoutube(m.youtube) }, '▸  Trailer'));
	const rt = await S.tomaten(typ, tmdb);
	const text = h('div', { class: 'text', onclick: () => text.classList.toggle('offen') }, t.text);
	el.append(h('div', { class: 'detail' }, h('div', { class: 'kulisse' }, img(t.hintergrund)),
		h('div', { class: 'kopfzeile' }, h('div', { class: 'poster' }, img(t.poster)),
			h('div', { class: 'titel' }, h('h1', {}, t.titel),
				h('div', { class: 'meta' }, [t.jahr, typ === 'movie' ? 'Film' : 'Serie', typ === 'movie' && m.laufzeit ? laufzeit(m.laufzeit * 6e8) : null, m.staffeln ? `${m.staffeln} Staffeln` : null, m.folgen ? `${m.folgen} Folgen` : null].filter(Boolean).join('   ·   ')),
				h('div', { class: 'meta' }, [m.genres.join(', '), m.bewertung ? `★ ${m.bewertung.toFixed(1)} (TMDB)` : null, rt !== null ? `Rotten Tomatoes ${rt} %` : null].filter(Boolean).join('   ·   ')),
				h('div', { class: 'knoepfe' }, kn), z === 'NOCH_NICHT' ? h('div', { class: 'meta' }, 'Bisher hat sich keine passende Quelle gefunden. Die Anfrage bleibt bestehen und wird weiter gesucht.') : null,
				meldungEl, text, m.macher ? h('div', { class: 'meta' }, (typ === 'movie' ? 'Regie: ' : 'Idee: ') + m.macher) : null, m.firmen ? h('div', { class: 'meta' }, m.firmen) : null))));
	el.append(reihe('Besetzung', m.besetzung, p => h('div', { class: 'person', onclick: () => geh(`#/seerrliste/person/${p.id}/${encodeURIComponent(p.name)}`) }, h('div', { class: 'bild' }, img(p.bild)), h('div', {}, p.name), h('div', { class: 'rolle' }, p.rolle || ''))) || '');
	const [em, ae] = await Promise.all([D.sicher(S.blatt(`/${typ}/${tmdb}/recommendations`, typ, 1).then(r => r.liste)), D.sicher(S.blatt(`/${typ}/${tmdb}/similar`, typ, 1).then(r => r.liste))]);
	if (el.isConnected) el.append(reihe('Empfehlungen', em, x => seerrKachel(x, seerrOeffnen)) || '', reihe('Ähnliche Titel', ae, x => seerrKachel(x, seerrOeffnen)) || '');
	// Automatisch nachsehen, solange es läuft (nicht bei «Noch nicht verfügbar»)
	if (['ANGEFRAGT', 'WIRD_GESUCHT', 'GLEICH'].includes(z)) setTimeout(() => { if (el.isConnected) neu(); }, 30000);
}
async function jellyfinFinden(t) {
	const r = await D.sicher(api('/Items', { params: { searchTerm: t.titel, recursive: true, includeItemTypes: t.typ === 'movie' ? 'Movie' : 'Series', fields: 'ProviderIds', limit: 30, userId: sitzung.benutzer.Id } }), {});
	return (r.Items || []).find(i => i.ProviderIds?.Tmdb === String(t.tmdb));
}
function trailerYoutube(k) {
	const zu = () => s.remove();
	const s = h('div', { class: 'schleier', onclick: zu }, h('iframe', { src: `https://www.youtube-nocookie.com/embed/${k}?autoplay=1`, allow: 'autoplay; fullscreen', style: 'width:min(1100px,94vw);aspect-ratio:16/9;border:0;border-radius:12px' }));
	document.body.append(s);
}

// ── Live-TV (Sprachblöcke, Reiter je Gerät gemerkt, alle 60 s neu) ──
export async function livetv(el) {
	el.append(h('div', { class: 'kopf' }, h('h1', {}, 'Live-TV'), h('button', { class: 'pille', onclick: () => geh('#/programm') }, '▦  Programmführer')));
	const reiter = h('div', { class: 'reiter' }), liste = h('div', {}, laden()); el.append(reiter, liste);
	const namen = await D.sicher(D.senderGruppenNamen(), {});
	let block = +(localStorage.getItem('seliku-sender-block') ?? -1);
	const uhr = (d) => d ? new Date(d.endsWith('Z') ? d : d + 'Z').toLocaleTimeString('de-CH', { hour: '2-digit', minute: '2-digit' }) : '';
	const zeile = (s, n) => {
		const p = s.CurrentProgram; const a = p?.StartDate && p?.EndDate ? (Date.now() - Date.parse(p.StartDate)) / (Date.parse(p.EndDate) - Date.parse(p.StartDate)) : null;
		return h('div', { class: 'zeile', onclick: () => abspielen(s) }, s.ImageTags?.Primary ? h('img', { class: 'logo', src: bild.url(s.Id, 'Primary', { breite: 160 }) }) : h('b', { class: 'logo' }, s.ChannelNumber),
			h('div', { class: 'bild' }, img(sendungsBild(s)), a !== null ? h('div', { class: 'fortschritt' }, h('i', { style: `width:${Math.min(100, Math.max(0, a * 100))}%` })) : null),
			h('div', {}, h('div', { class: 'leise' }, `${s.Name}  ·  Kanal ${s.ChannelNumber || '–'}`), h('b', {}, p?.Name || 'Kein Programm'),
				p ? h('div', { class: 'leise' }, `${uhr(p.StartDate)}–${uhr(p.EndDate)}`) : null, n ? h('div', { class: 'leise' }, `Danach ${uhr(n.StartDate)} · ${n.Name}`) : null));
	};
	const zeichnen = async () => {
		const sender = await D.sicher(D.sender()); const danach = await D.sicher(D.naechsteSendungen(sender), {});
		const bloecke = [...new Set(sender.map(s => D.senderBlock(s.ChannelNumber ?? s.Number)))].sort((a, b) => a - b);
		const name = (b) => b === Infinity ? 'Weitere Sender' : namen[b] || (b === 0 ? 'Sender 1–99' : `Sender ${b * 100}–${b * 100 + 99}`);
		const gruppiert = E.an('sender_gruppen') && bloecke.length > 1;
		reiter.replaceChildren(...(gruppiert ? [-1, ...bloecke].map(b => h('button', { class: 'pille klein' + (b === block ? ' aktiv' : ''), onclick: () => { block = b; localStorage.setItem('seliku-sender-block', b); zeichnen(); } }, b === -1 ? 'Alle' : name(b))) : []));
		const teile = !gruppiert ? [[null, sender]] : (block === -1 ? bloecke : [block]).map(b => [b, sender.filter(s => D.senderBlock(s.ChannelNumber ?? s.Number) === b)]);
		liste.replaceChildren(...teile.flatMap(([b, l]) => [gruppiert && block === -1 ? h('h2', { style: 'padding:0 var(--rand);font-size:17px' }, `${name(b)} · ${l.length} Sender`) : '', ...l.map(s => zeile(s, danach[s.Id]))]));
	};
	await zeichnen();
	const t = setInterval(() => el.isConnected ? zeichnen() : clearInterval(t), 60000);
}

// ── Musik ──
const MIXE = [['zufall', 'Zufallsmix', 'Random', 'Descending'], ['neu', 'Neu hinzugefügt', 'DateCreated', 'Descending'], ['meist', 'Meistgehört', 'PlayCount', 'Descending', 'IsPlayed'], ['lange', 'Lange nicht gehört', 'DatePlayed', 'Ascending', 'IsPlayed']];
const FARBEN = [['#FFB347', '#FF6B4A'], ['#F08A4B', '#A9552C'], ['#E76F51', '#8E3B2E'], ['#F4A261', '#B5651D']];
export async function musik(el, reiterWahl = 'uebersicht') {
	const alleB = await D.bibliotheken(); const bibs = alleB.filter(b => b.CollectionType === 'music');
	const bib = bibs.find(b => !/podcast/i.test(b.Name)) || bibs[0];
	const hoerBibs = [...bibs.filter(b => /podcast/i.test(b.Name)).map(b => [b, true]), ...alleB.filter(b => b.CollectionType === 'books').map(b => [b, false])];
	if (!bib) { el.append(kopf('Musik'), h('div', { class: 'hinweis' }, 'Keine Musikbibliothek.')); return; }
	el.append(kopf(bib.Name), h('div', { class: 'reiter' }, [['uebersicht', 'Übersicht'], ['playlists', 'Playlists'], ['interpreten', 'Interpreten'], ['alben', 'Alben'], ['radio', 'Radio'], ...hoerBibs.map(([b]) => ['h' + b.Id, b.Name])]
		.map(([k, t]) => h('button', { class: 'pille' + (k === reiterWahl ? ' aktiv' : ''), onclick: () => geh(`#/musik/${k}`) }, t))));
	const inhalt = h('div', {}, laden()); el.append(inhalt);
	const p = { userId: sitzung.benutzer.Id, enableUserData: true, fields: 'PrimaryImageAspectRatio' };
	const mixKachel = ([k, name, sort, ord, filt], n) => h('div', { class: 'kachel quadrat', onclick: async () => musikSpielen((await api('/Items', { params: { ...p, parentId: bib.Id, includeItemTypes: 'Audio', recursive: true, sortBy: sort, sortOrder: ord, filters: filt, limit: 100 } })).Items) },
		h('div', { class: 'bild', style: `background:linear-gradient(135deg,${FARBEN[n % 4][0]},${FARBEN[n % 4][1]});display:flex;align-items:flex-end;padding:12px` }, h('b', { style: 'color:#000;font-size:17px' }, name)));
	if (reiterWahl === 'uebersicht') {
		const [zul, neu, pl] = await Promise.all([api('/Items', { params: { ...p, parentId: bib.Id, includeItemTypes: 'Audio', recursive: true, sortBy: 'DatePlayed', sortOrder: 'Descending', filters: 'IsPlayed', limit: 30 } }).then(r => r.Items),
			D.neuIn(bib), D.sicher(api('/Items', { params: { ...p, includeItemTypes: 'Playlist', recursive: true, sortBy: 'SortName', fields: 'Path' } }).then(r => r.Items.filter(x => !(x.Path || '').startsWith('/mnt/'))))]);
		inhalt.replaceChildren(...[reihe('Zuletzt gehört', zul, i => kachel(i, 'quadrat', () => musikSpielen(zul, zul.indexOf(i)))), reihe('Neu hinzugefügt', neu, i => kachel(i, 'quadrat', oeffnen)),
			reihe('Automatische Mixe', MIXE, mixKachel), reihe('Deine Playlists', pl.slice(0, 20), i => kachel(i, 'quadrat', oeffnen))].filter(Boolean));
	} else if (reiterWahl === 'radio') radio(inhalt);
	else if (reiterWahl.startsWith('h')) {
		const [hb, pod] = hoerBibs.find(([b]) => 'h' + b.Id === reiterWahl) || [];
		if (!hb) return;
		const [l, weiter, neue] = await Promise.all([D.ordnerInhalt(hb.Id), D.sicher(H.weiterhoeren()), pod ? api('/Items', { params: { parentId: hb.Id, recursive: true, includeItemTypes: 'Audio', filters: 'IsUnplayed', sortBy: 'PremiereDate,DateCreated', sortOrder: 'Descending', limit: 20, userId: sitzung.benutzer.Id, enableUserData: true } }).then(r => r.Items) : []]);
		inhalt.replaceChildren(...[reihe('Weiterhören', weiter, i => kachel(i, 'quadrat', hoerenStarten)), pod ? reihe('Neue Folgen', neue, i => kachel(i, 'quadrat', folgeSpielen)) : null,
			h('h2', { style: 'padding:14px var(--rand) 0;font-size:17px' }, pod ? 'Alle Podcasts' : 'Alle Hörbücher'),
			h('div', { class: 'raster' }, l.map(i => kachel(i, 'quadrat', () => geh((pod ? '#/podcast/' : '#/buch/') + i.Id))))].filter(Boolean));
	}
	else {
		const r = reiterWahl === 'interpreten' ? await api('/Artists/AlbumArtists', { params: { ...p, parentId: bib.Id, sortBy: 'SortName' } })
			: reiterWahl === 'alben' ? await api('/Items', { params: { ...p, parentId: bib.Id, includeItemTypes: 'MusicAlbum', recursive: true, sortBy: 'SortName' } })
			: await api('/Items', { params: { ...p, includeItemTypes: 'Playlist', recursive: true, sortBy: 'SortName', fields: 'Path' } });
		const l = reiterWahl === 'playlists' ? r.Items.filter(x => !(x.Path || '').startsWith('/mnt/')) : r.Items;
		inhalt.replaceChildren(h('div', { class: 'raster' }, reiterWahl === 'playlists' ? MIXE.map(mixKachel) : [], l.map(i => kachel(i, reiterWahl === 'interpreten' ? 'rund' : 'quadrat', oeffnen))));
	}
}
export async function album(el, id) {
	const a = await D.item(id); const t = await titelVon(a);
	el.append(kopf(a.Name, true), h('div', { style: 'display:flex;gap:28px;padding:8px var(--rand) 20px;align-items:flex-end' }, h('div', { class: 'kachel quadrat', style: 'width:220px' }, h('div', { class: 'bild' }, img(bild.poster(a, 500)))),
		h('div', {}, h('div', { class: 'meta', style: 'color:var(--leise)' }, unterzeile(a)), h('div', { class: 'knoepfe', style: 'display:flex;gap:10px;margin-top:12px' },
			h('button', { class: 'pille haupt', onclick: () => musikSpielen(t, 0) }, '▶  Abspielen'), h('button', { class: 'pille', onclick: () => musikSpielen(t, 0, true) }, '⤨  Zufällig')))),
		...t.map((x, n) => h('div', { class: 'zeile', onclick: () => musikSpielen(t, n), oncontextmenu: (e) => { e.preventDefault(); titelMenue(x); } }, h('span', { class: 'leise', style: 'width:28px' }, x.IndexNumber ?? n + 1),
			h('div', { style: 'flex:1' }, h('div', {}, x.Name), h('div', { class: 'leise' }, (x.Artists || []).join(', '))), h('span', { class: 'leise' }, zeit((x.RunTimeTicks || 0) / 1e4)))));
}
export async function interpret(el, id) {
	const k = await D.item(id);
	const [alben, beliebt] = await Promise.all([api('/Items', { params: { userId: sitzung.benutzer.Id, albumArtistIds: id, includeItemTypes: 'MusicAlbum', recursive: true, sortBy: 'ProductionYear,SortName', sortOrder: 'Descending' } }).then(r => r.Items),
		api('/Items', { params: { userId: sitzung.benutzer.Id, artistIds: id, includeItemTypes: 'Audio', recursive: true, sortBy: 'PlayCount,CommunityRating', sortOrder: 'Descending', limit: 10 } }).then(r => r.Items)]);
	el.append(kopf(k.Name, true), h('div', { class: 'reiter' }, h('button', { class: 'pille haupt', onclick: async () => musikSpielen(await titelVon(k), 0) }, '▶  Abspielen'), h('button', { class: 'pille', onclick: async () => musikSpielen(await titelVon(k), 0, true) }, '⤨  Zufällig')),
		h('h2', { style: 'padding:0 var(--rand);font-size:17px' }, 'Beliebt'), ...beliebt.map((x, n) => h('div', { class: 'zeile', onclick: () => musikSpielen(beliebt, n) }, h('span', { class: 'leise' }, n + 1), h('div', { style: 'flex:1' }, x.Name), h('span', { class: 'leise' }, zeit((x.RunTimeTicks || 0) / 1e4)))),
		reihe('Alben', alben, i => kachel(i, 'quadrat', oeffnen)) || '');
}
// Radio: radio-browser.info, Favoriten im Konto («seliku-radio», gleiche Liste wie am TV)
const RB = 'https://de1.api.radio-browser.info/json/';
const rbHolen = async (p) => (await (await fetch(RB + p)).json()).map(o => ({ id: o.stationuuid, name: o.name.trim(), url: o.url_resolved || o.url, logo: o.favicon?.startsWith('http') ? o.favicon : null, land: o.countrycode, tags: o.tags })).filter(s => s.url && s.name);
export async function radio(el) {
	let fav = JSON.parse((await D.sicher(D.praef('seliku-radio'), {})).CustomPrefs?.favoriten || '[]').map(o => ({ id: o.id || o.stationuuid, name: o.name, url: o.url, logo: o.logo || null, land: o.land, tags: o.tags }));
	const favSetzen = async (s) => {
		fav = fav.some(x => x.id === s.id) ? fav.filter(x => x.id !== s.id) : [...fav, s];
		await D.praefSetzen('seliku-radio', 'favoriten', JSON.stringify(fav.map(x => ({ id: x.id, name: x.name, url: x.url, logo: x.logo || '', land: x.land, tags: x.tags }))));
		meldung(fav.some(x => x.id === s.id) ? 'Zu Radio-Favoriten' : 'Aus Radio-Favoriten entfernt');
	};
	const k = (s) => h('div', { class: 'kachel quadrat', title: 'Rechtsklick: Favorit', onclick: () => radioSpielen(s), oncontextmenu: (e) => { e.preventDefault(); favSetzen(s); } },
		h('div', { class: 'bild', style: 'display:grid;place-items:center' }, s.logo ? h('img', { src: s.logo, style: 'object-fit:contain;padding:14px', onerror: (e) => e.target.remove() }) : h('b', {}, s.name.slice(0, 2))), h('div', { class: 'z1' }, s.name));
	const feld = h('input', { type: 'search', placeholder: 'Sender suchen' }); const treffer = h('div');
	let zt; feld.addEventListener('input', () => { clearTimeout(zt); zt = setTimeout(async () => treffer.replaceChildren(feld.value.length >= 2 ? reihe('Treffer', await rbHolen(`stations/search?name=${encodeURIComponent(feld.value)}&order=clickcount&reverse=true&hidebroken=true&limit=40`), k) || '' : ''), 450); });
	el.replaceChildren(h('div', { class: 'suchfeld' }, feld), treffer, reihe('Deine Favoriten', fav, k) || '', h('div', {}, laden()));
	const [ch, ...g] = await Promise.all([rbHolen('stations/bycountrycodeexact/CH?order=clickcount&reverse=true&hidebroken=true&limit=40'), ...['pop', 'rock', 'jazz', 'classical', 'news'].map(t => rbHolen(`stations/bytagexact/${t}?order=clickcount&reverse=true&hidebroken=true&limit=20`).then(l => [t, l]))]);
	el.lastChild.replaceWith(h('div', {}, reihe('Beliebt in der Schweiz', ch, k), ...g.map(([t, l]) => reihe(t[0].toUpperCase() + t.slice(1), l, k))));
}

// ── Mein Seliku, Meine Liste, Ordner, Bibliotheken, Einstellungen ──
export async function mein(el) {
	const u = sitzung.benutzer;
	const kopfEl = h('div', { class: 'kopf', style: 'gap:22px' }, h('div', { style: 'width:88px;height:88px;border-radius:50%;background:var(--akzent-dunkel);display:grid;place-items:center;font-weight:800;font-size:28px;overflow:hidden' },
		u.PrimaryImageTag ? h('img', { src: `${sitzung.server}/Users/${u.Id}/Images/Primary?maxWidth=200`, style: 'width:100%;height:100%;object-fit:cover' }) : u.Name.slice(0, 2).toUpperCase()), h('div', {}, h('h1', {}, u.Name), h('div', { class: 'meta', style: 'color:var(--leise)' }, ' ')));
	el.append(kopfEl); const inhalt = h('div', {}, laden()); el.append(inhalt);
	const [w, l, gef, v, nicht, gen] = await Promise.all([D.sicher(D.weiterschauen()), D.sicher(D.favoriten()), D.sicher(D.gemochteTitel()), D.sicher(D.verlauf()), D.sicher(D.nichtGemocht()), D.sicher(D.lieblingsGenres())]);
	const minuten = (ab) => Math.round(v.filter(i => new Date(i.UserData.LastPlayedDate + (i.UserData.LastPlayedDate.endsWith('Z') ? '' : 'Z')) >= ab).reduce((s, i) => s + ((i.UserData.Played ? i.RunTimeTicks : i.UserData.PlaybackPositionTicks) || 0) / 6e8, 0));
	const heute = new Date(); heute.setHours(0, 0, 0, 0); const mo = new Date(heute); mo.setDate(heute.getDate() - ((heute.getDay() + 6) % 7)); const mon = new Date(heute.getFullYear(), heute.getMonth(), 1);
	const dauer = (m) => m >= 60 ? `${Math.floor(m / 60)} Std. ${m % 60} Min.` : `${m} Min.`;
	kopfEl.querySelector('.meta').replaceChildren(`Diese Woche ca. ${dauer(minuten(mo))} · Diesen Monat ca. ${dauer(minuten(mon))}`, h('div', { style: 'display:flex;gap:6px;margin-top:8px' }, gen.slice(0, 4).map(g => h('span', { class: 'pille klein aktiv' }, g))));
	inhalt.replaceChildren(...[reihe('Weiterschauen', w, i => kachel(i, 'quer', abspielen)), reihe('Meine Liste', l, i => kachel(i, 'poster', oeffnen)), reihe('Serien und Filme, die dir gefallen', gef, i => kachel(i, 'poster', oeffnen)),
		reihe('Zuletzt gesehen', D.eindeutig(v.filter(i => i.UserData.Played), i => i.SeriesId || i.Id).slice(0, 30), i => kachel(i, 'poster', oeffnen)), reihe('Nicht mehr empfehlen', nicht, i => kachel(i, 'poster', oeffnen))].filter(Boolean),
		h('div', { class: 'reiter', style: 'margin-top:30px' }, h('button', { class: 'pille', onclick: abmelden }, '⇄  Abmelden / Profil wechseln'),
			u.Policy?.IsAdministrator ? h('button', { class: 'pille', onclick: () => klassischOeffnen('#/dashboard') }, '⚙  Server verwalten') : null,
			h('button', { class: 'pille', onclick: () => klassischOeffnen('#/home') }, 'Klassische Jellyfin-Ansicht')));
}
export async function liste(el) {
	el.append(kopf('Meine Liste')); const l = await D.favoriten();
	el.append(l.length ? h('div', { class: 'raster' }, l.map(i => kachel(i, 'poster', oeffnen))) : h('div', { class: 'hinweis' }, 'Noch nichts auf der Liste. Mit ♡ auf einer Detailseite hinzufügen.'));
}
export async function ordner(el, id) {
	const o = await D.item(id); const l = await D.ordnerInhalt(id);
	const audio = l.filter(i => i.Type === 'Audio' || i.Type === 'AudioBook');
	el.append(kopf(o.Name, true), audio.length ? h('div', { class: 'reiter' }, h('button', { class: 'pille haupt', onclick: () => musikSpielen(audio, 0) }, '▶  Alle abspielen')) : '',
		h('div', { class: 'raster' }, l.filter(i => !audio.includes(i)).map(i => kachel(i, i.IsFolder ? 'poster' : 'quer', oeffnen))),
		...audio.map((x, n) => h('div', { class: 'zeile', onclick: () => musikSpielen(audio, n) }, h('div', { style: 'flex:1' }, x.Name), h('span', { class: 'leise' }, zeit((x.RunTimeTicks || 0) / 1e4)))));
}
export async function bibliotheken(el) {
	el.append(kopf('Bibliotheken'));
	const l = (await D.bibliotheken()).filter(b => b.CollectionType !== 'playlists').sort((a, b) => D.gruppeFuer(a) - D.gruppeFuer(b));
	el.append(h('div', { class: 'raster', style: 'grid-template-columns:repeat(auto-fill,minmax(260px,1fr))' }, l.map(b => kachel(b, 'quer', () => geh(bibZiel(b)), { z1: b.ImageTags?.Primary ? '' : b.Name, z2: '', bildUrl: bild.url(b.Id, 'Primary', { breite: 600 }) }))));
}
export const bibZiel = (b) => b.CollectionType === 'livetv' ? '#/livetv' : b.CollectionType === 'music' ? '#/musik' : ['movies', 'tvshows', 'boxsets', 'musicvideos'].includes(b.CollectionType) ? `#/bib/${b.Id}` : `#/ordner/${b.Id}`;
export async function einstellungen(el) {
	await E.holen();
	el.append(kopf('Seliku-Einstellungen'), h('div', { class: 'hinweis', style: 'padding-top:0;padding-bottom:6px' }, '☁ = für alle Geräte (über das Konto), 💻 = nur dieser Browser. Antippen wechselt.'));
	const gruppen = {}; E.SCHALTER.forEach(s => (gruppen[s.gruppe] ||= []).push(s));
	for (const [g, l] of Object.entries(gruppen)) {
		el.append(h('h2', { style: 'padding:14px var(--rand) 0;font-size:17px' }, g));
		for (const s of l) {
			const kn = h('button', { class: 'knipser' + (E.an(s.k) ? ' an' : ''), onclick: () => { E.setzen(s.k, !E.an(s.k)); kn.classList.toggle('an', E.an(s.k)); } });
			const um = h('span', { class: 'umfang', title: 'Für alle Geräte oder nur hier', onclick: () => { E.umfangWechseln(s); um.textContent = E.alle(s) ? '☁' : '💻'; } }, E.alle(s) ? '☁' : '💻');
			el.append(h('div', { class: 'schalter' }, h('div', { class: 't' }, h('b', {}, s.titel), h('small', {}, s.text)), um, kn));
		}
	}
	const nf = E.wert('naechste_folge', 'true');
	el.append(h('h2', { style: 'padding:14px var(--rand) 0;font-size:17px' }, 'Wiedergabe in diesem Browser'), h('div', { class: 'reiter' }, h('b', { style: 'align-self:center;margin-right:8px' }, 'Nach einer Folge:'),
		[['true', 'Nächste startet'], ['warten', 'Wartet auf Klick'], ['false', 'Schluss']].map(([w, t]) => h('button', { class: 'pille klein' + (w === nf ? ' aktiv' : ''), onclick: () => { E.setzen('naechste_folge', w); el.replaceChildren(); einstellungen(el); } }, t))),
		h('div', { class: 'reiter', style: 'margin-top:18px' }, h('a', { class: 'pille haupt', href: 'seliku-handy.bin', download: 'seliku-handy.apk' }, '📱  Seliku-App fürs Android-Handy laden')),
		h('div', { class: 'hinweis' }, `Seerr: ${S.seerr.verbunden ? 'verbunden über Jellyfin (ohne eigene Anmeldung)' : 'nicht verbunden'}`));
}

// ── Hören: Buch- und Podcastseiten ──
export async function hoerenStarten(i) { await H.laden(); if (i.Type === 'Audio') return folgeSpielen(i); return buchSpielen(i); }
export async function buch(el, id) {
	await H.laden(); const b = await D.item(id); const d = await H.buchDateien(id); const G = gid(id);
	if (!d.length) return geh(`#/ordner/${id}`);   // kein Buch, sondern z. B. ein Autor mit mehreren Büchern
	const st = H.wert('b:' + G)?.split('|'), fertig = !!H.wert('f:' + G);
	let info = fertig ? '✓ Gehört' : '';
	if (st) { const n = Math.max(0, d.findIndex(x => gid(x.Id) === st[0])); const ges = d.reduce((s, x) => s + (x.RunTimeTicks || 0) / 1e4, 0); const bis = d.slice(0, n).reduce((s, x) => s + (x.RunTimeTicks || 0) / 1e4, 0) + +st[1];
		info = `${Math.round(bis / ges * 100)} % · Kapitel ${n + 1} von ${d.length} · noch ${zeit(Math.max(0, ges - bis))}`; }
	el.append(kopf(b.Name, true), h('div', { style: 'display:flex;gap:28px;padding:8px var(--rand) 20px;align-items:flex-end;flex-wrap:wrap' },
		h('div', { class: 'kachel quadrat', style: 'width:220px' }, h('div', { class: 'bild' }, img(bild.poster(b, 500)))),
		h('div', {}, h('div', { style: 'color:var(--leise)' }, info), h('div', { style: 'display:flex;gap:10px;margin-top:12px;flex-wrap:wrap' },
			h('button', { class: 'pille haupt', onclick: () => buchSpielen(b) }, st ? '▶  Weiterhören' : '▶  Abspielen'),
			st ? h('button', { class: 'pille', onclick: () => { H.setzen('b:' + G, null); buchSpielen(b, 0); } }, '↺  Von vorn') : null,
			h('button', { class: 'pille', onclick: async () => { H.setzen('f:' + G, fertig ? null : String(Date.now())); if (!fertig) H.setzen('b:' + G, null); await H.hochladen(); el.replaceChildren(); buch(el, id); } }, fertig ? '○  Als ungehört' : '✓  Als gehört')))),
		...d.map((x, n) => h('div', { class: 'zeile', onclick: () => buchSpielen(b, n) }, h('span', { class: 'leise', style: 'width:28px' }, n + 1), h('div', { style: 'flex:1;' + (st?.[0] === gid(x.Id) ? 'color:var(--akzent)' : '') }, x.Name), h('span', { class: 'leise' }, zeit((x.RunTimeTicks || 0) / 1e4)))));
}
export async function podcast(el, id) {
	await H.laden(); const p = await D.item(id);
	const f = (await api('/Items', { params: { parentId: id, recursive: true, includeItemTypes: 'Audio', sortBy: 'PremiereDate,DateCreated,SortName', sortOrder: 'Descending', userId: sitzung.benutzer.Id, enableUserData: true } })).Items;
	const angef = f.find(x => H.wert('p:' + gid(x.Id)));
	const datum = (x) => { const d = x.PremiereDate || x.DateCreated; return d ? new Date(d).toLocaleDateString('de-CH') : ''; };
	el.append(kopf(p.Name, true), h('div', { class: 'reiter' }, (angef || f[0]) ? h('button', { class: 'pille haupt', onclick: () => folgeSpielen(angef || f[0]) }, angef ? '▶  Weiterhören' : '▶  Neueste abspielen') : null),
		...f.map(x => { const pos = +(H.wert('p:' + gid(x.Id))?.split('|')[0] || 0), d = (x.RunTimeTicks || 0) / 1e4, g = H.gehoert(x);
			return h('div', { class: 'zeile', title: 'Rechtsklick: gehört ↔ ungehört', onclick: () => folgeSpielen(x), oncontextmenu: async (e) => { e.preventDefault(); H.setzen('p:' + gid(x.Id), null); await api(`/UserPlayedItems/${x.Id}`, { methode: g ? 'DELETE' : 'POST', params: { userId: sitzung.benutzer.Id } }); await H.hochladen(); el.replaceChildren(); podcast(el, id); } },
				h('div', { style: 'flex:1' }, h('div', { class: 'leise' }, datum(x)), h('b', { style: g ? 'color:var(--leise)' : '' }, (g ? '✓  ' : '') + x.Name),
					h('div', { class: 'leise' }, [laufzeit(x.RunTimeTicks), pos ? `noch ${zeit(Math.max(0, d - pos))}` : null].filter(Boolean).join(' · ')),
					pos && d ? h('div', { style: 'height:3px;background:var(--flaeche-hell);margin-top:4px' }, h('div', { style: `height:3px;width:${pos / d * 100}%;background:var(--akzent)` })) : null)); }));
}
