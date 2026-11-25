// Seliku-Einstellungen wie am TV und auf dem Handy: gleiche Schlüssel, Abgleich über das Konto
// (DisplayPreferences «seliku-einstellungen»). Server gewinnt beim Holen; fremde Schlüssel bleiben unberührt.
import { praef, praefSetzen, sicher } from './daten.js';
import { api, sitzung } from './api.js';

export const SCHALTER = [
	['favoriten_reihe', 'Deine Favoriten', 'Reihe «Deine Favoriten» auf der Startseite', true, true, 'Startseite'],
	['wahre_geschichten', 'Wahre Geschichten', 'Filme und Serien nach wahren Begebenheiten', true, true, 'Startseite'],
	['banner_seerr', 'Fehlende Titel im Banner', '1–2 Vorschläge, die es noch nicht gibt (zum Anfragen)', true, true, 'Startseite'],
	['trailer_banner', 'Trailer im Banner', 'Trailer startet stumm, wenn die Maus auf dem Banner ruht', true, true, 'Startseite'],
	['hoeren_start', 'Weiterhören', 'Hörbücher und Podcasts auf der Startseite', true, true, 'Startseite'],
	['mein_verlauf', 'Mein Seliku', 'Profilseite mit Verlauf, Meine Liste und Sehzeit', true, true, 'Startseite'],
	['weiterschauen_geraet', 'Weiterschauen nur von diesem Browser', 'Zeigt nur, was hier angefangen wurde', false, false, 'Startseite'],
	['uhr_film', 'Uhr im Film', 'Uhrzeit unten links während der Wiedergabe, fast durchsichtig', true, true, 'Wiedergabe'],
	['titelmusik', 'Titelmusik', 'Leise Titelmusik auf Serienseiten', true, true, 'Serien'],
	['zufall_knopf', 'Knopf «Zufällig»', 'Zufällige Folge direkt auf der Serienseite', false, true, 'Serien'],
	['eigene_wertung', 'Eigene Sterne', 'Titel mit 1–5 Sternen bewerten', true, true, 'Inhalte'],
	['tomaten', 'Rotten Tomatoes', 'Kritikerwert neben dem Titel', true, true, 'Inhalte'],
	['loeschen', 'Löschen erlauben', '«Löschen» im Titelmenü (wenn das Konto darf)', true, true, 'Inhalte'],
	['sender_gruppen', 'Sender gruppieren', 'Live-TV nach Sprachblöcken (1–99, 100–199 …)', true, true, 'Inhalte'],
].map(([k, titel, text, vorgabe, alle, gruppe]) => ({ k, titel, text, vorgabe, alle, gruppe }));

const lokal = () => JSON.parse(localStorage.getItem('seliku-einstellungen') || '{}');
export function an(k) { const w = lokal()[k]; if (w !== undefined) return w === 'true' || w === true; return SCHALTER.find(s => s.k === k)?.vorgabe ?? false; }
export function wert(k, vorgabe) { return lokal()[k] ?? vorgabe; }
export function alle(s) { const u = localStorage.getItem('seliku-umfang-' + s.k); return u ? u === 'alle' : s.alle; }
export function umfangWechseln(s) { localStorage.setItem('seliku-umfang-' + s.k, alle(s) ? 'geraet' : 'alle'); if (alle(s)) hochladen(); }
export function setzen(k, w) {
	const l = lokal(); l[k] = String(w); localStorage.setItem('seliku-einstellungen', JSON.stringify(l));
	const s = SCHALTER.find(x => x.k === k); if (!s || alle(s)) hochladen();
}
export async function holen() {
	const server = (await sicher(praef('seliku-einstellungen'), {})).CustomPrefs || {};
	const l = lokal(); let fehlt = false;
	for (const s of SCHALTER.filter(alle)) { if (server[s.k] === undefined) fehlt = true; else l[s.k] = server[s.k]; }
	localStorage.setItem('seliku-einstellungen', JSON.stringify(l));
	if (fehlt) hochladen();
}
async function hochladen() {
	try {
		const alt = await praef('seliku-einstellungen');
		for (const s of SCHALTER.filter(alle)) alt.CustomPrefs[s.k] = String(an(s.k));
		await api('/DisplayPreferences/seliku-einstellungen', { methode: 'POST', params: { client: 'seliku', userId: sitzung.benutzer.Id }, body: alt });
	} catch {}
}
