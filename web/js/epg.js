// Programmführer (am TV kommt er von Jellyfin, hier selbst gebaut): Sender nach Nummer, 12 Stunden ab jetzt,
// Sprachblock wie in der Live-TV-Seite. Antippen einer laufenden Sendung schaltet ein, sonst Infos.
import { api, sitzung, bild } from './api.js';
import * as D from './daten.js';
import * as E from './einstellungen.js';
import { h, laden, dialog, meldung } from './ui.js';
import { abspielen } from './navigation.js';

const PX = 6; // Pixel je Minute
const utc = (d) => new Date(d.endsWith('Z') ? d : d + 'Z');
const uhr = (d) => d.toLocaleTimeString('de-CH', { hour: '2-digit', minute: '2-digit' });

export async function programm(el) {
	el.append(h('div', { class: 'kopf' }, h('button', { class: 'pille', onclick: () => history.back() }, '←'), h('h1', {}, 'Programmführer')));
	const box = h('div', {}, laden()); el.append(box);
	const start = new Date(); start.setMinutes(start.getMinutes() < 30 ? 0 : 30, 0, 0);
	const ende = new Date(start.getTime() + 12 * 3600e3);
	const block = +(localStorage.getItem('seliku-sender-block') ?? -1);
	let sender = await D.sicher(D.sender());
	if (E.an('sender_gruppen') && block >= 0) sender = sender.filter(s => D.senderBlock(s.ChannelNumber ?? s.Number) === block);
	const p = await api('/LiveTv/Programs', { params: { userId: sitzung.benutzer.Id, channelIds: sender.map(s => s.Id).join(','), maxStartDate: ende.toISOString(), minEndDate: start.toISOString(), sortBy: 'StartDate', enableImages: false } }).catch(() => ({ Items: [] }));
	const jeSender = {}; for (const x of p.Items || []) (jeSender[x.ChannelId] ||= []).push(x);
	const breite = (ende - start) / 60000 * PX;
	const kopf = h('div', { class: 'zeitkopf' }, h('div', { class: 'ecke' }), h('div', { class: 'spur', style: `width:${breite}px` },
		Array.from({ length: 24 }, (_, i) => h('span', { style: `left:${i * 30 * PX + 4}px` }, uhr(new Date(start.getTime() + i * 30 * 60000))))));
	const jetzt = Date.now();
	const zeilen = sender.map(s => h('div', { class: 'zeile' },
		h('div', { class: 'sender', title: 'Einschalten', onclick: () => abspielen(s) }, s.ImageTags?.Primary ? h('img', { src: bild.url(s.Id, 'Primary', { breite: 120 }), alt: '' }) : h('b', {}, s.ChannelNumber), h('span', {}, s.Name)),
		h('div', { class: 'spur', style: `width:${breite}px` }, (jeSender[s.Id] || []).map(x => {
			const a = Math.max(utc(x.StartDate), start), b = Math.min(utc(x.EndDate), ende);
			const laeuft = utc(x.StartDate) <= jetzt && utc(x.EndDate) > jetzt;
			return h('div', { class: 'sendung' + (laeuft ? ' jetzt' : ''), style: `left:${(a - start) / 60000 * PX}px;width:${Math.max(4, (b - a) / 60000 * PX - 2)}px`, title: x.Name,
				onclick: () => laeuft ? abspielen(s) : info(x, s) }, h('b', {}, x.Name), h('small', {}, `${uhr(utc(x.StartDate))}–${uhr(utc(x.EndDate))}`));
		}))));
	const linie = h('div', { class: 'jetztlinie', style: `left:${150 + (jetzt - start) / 60000 * PX}px` });
	const epg = h('div', { class: 'epg' }, kopf, ...zeilen, linie);
	box.replaceChildren(sender.length ? epg : h('div', { class: 'hinweis' }, 'Keine Sender.'));
	epg.scrollLeft = Math.max(0, (jetzt - start) / 60000 * PX - 120);
}
async function info(x, s) {
	const d = await api(`/LiveTv/Programs/${x.Id}`, { params: { userId: sitzung.benutzer.Id } }).catch(() => x);
	const darf = sitzung.benutzer.Policy?.EnableLiveTvManagement;
	const zu = dialog([h('h3', {}, d.Name), h('div', { class: 'u' }, `${s.Name} · ${uhr(utc(d.StartDate))}–${uhr(utc(d.EndDate))}`), h('div', { class: 'u' }, d.Overview || ''),
		darf ? h('button', { onclick: async () => { zu(); try { const t = await api('/LiveTv/Timers/Defaults', { params: { programId: d.Id } }); await api('/LiveTv/Timers', { methode: 'POST', body: t }); meldung('Aufnahme geplant'); } catch { meldung('Aufnahme nicht möglich'); } } }, '●  Aufnehmen') : null]);
}
