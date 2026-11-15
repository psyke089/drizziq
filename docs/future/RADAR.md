# Radar: Verbesserungen, Datenquellen und technische Bestandsaufnahme

Recherche vom **2026-11-05**, bezogen auf Drizziq **1.6.4**, Commit `2af89b3`.
Dieses Dokument beschreibt Vorschläge und Befunde; der Umsetzungsstand unten
ist nachgetragen. Die Recherche und ihre Dokumentation ändern keinen
Anwendungscode und integrieren keine neuen Dienste.

## 0. Umsetzungsstand 2026-11-15

Die Befunde aus §3 und §4 sind umgesetzt, die Bedienlücken aus §5 teilweise:

- Decode-Anfragen werden zusammengefasst und vor dem Decode auf Aktualität
  geprüft; Downloadfortschritt wird auf höchstens vier Updates pro Sekunde
  gebündelt.
- Gespeicherte Kacheln erscheinen sofort, die bedingte Revalidierung folgt im
  Hintergrund. Nur sichtbare Kacheln werden angefragt, höchstens 16 warten
  gleichzeitig, und gröbere Zoomstufen dienen als Platzhalter.
- Der aktuelle TAR-Lauf wird während des Downloads gelesen; die aktuelle
  Beobachtung wird vor dem Rest des Archivs veröffentlicht, danach folgt die
  Historie.
- Frisch dekodierte Bitmaps gehen direkt an die Anzeige; das PNG bleibt nur
  Persistenz für spätere Starts.
- Das Viewport bleibt am Gestenende synchron gespeichert: ein Force-Stop hat
  keinen Lifecycle-Callback, ein verzögertes Schreiben würde die letzte Kamera
  verlieren. Zusätzliche Schreibvorgänge pro Frame wurden vermieden.
- Radar-Dateien werden erst nach erfolgreichem Manifest-Schreiben bereinigt;
  referenzierte Frames bleiben immer erhalten.
- Der Access-Index wird gebündelt statt pro Kachel-Lesung geschrieben.
- Laufende Radararbeit wird bei `onStop` abgebrochen; gespeicherte Frames
  bleiben unverändert.
- Die Radar-UI kennzeichnet Beobachtungen und Prognosen sichtbar, markiert die
  Grenze auf der Zeitachse und bietet einen Jetzt-Sprung sowie 0,5×/1×/2×.

Offen bleiben die Datenquellen und Funktionen aus §5 (CAP, HymecNG, KONRAD3D,
RADOLAN/HX, Ortskurve, Farblegende); §6 und §7 bleiben Vorschläge.

## 1. Empfehlung und Untersuchungsgrenzen

Das Gefühl, der Radar-Tab könne schneller reagieren, ist anhand des Codes
plausibel. Es gibt konkrete Möglichkeiten, überholte Arbeit zu vermeiden,
früher vorhandene Bilder anzuzeigen und die Oberfläche seltener unnötig zu
aktualisieren. Zuerst diese Punkte verbessern, anschließend neue Wetterdaten
ergänzen.

Untersucht wurden Radar-, Download-, Karten- und Cache-Code, vorhandene Tests,
Projektverlauf und Screenshots sowie öffentliche Anbieterinformationen. Für
eigene Laufzeitmessungen war kein Telefon angeschlossen. Deshalb gilt:

- **Codebefund:** Der beschriebene Ablauf ist im untersuchten Stand vorhanden.
- **Performance-Einschätzung:** Seine Auswirkung auf das übliche Telefon ist
  plausibel, aber noch nicht gemessen. Es gibt keine belegten Millisekunden-
  oder FPS-Gewinne und keinen Nachweis eines dauerhaften Speicherlecks.
- Vorhandene funktionale Phone-Checks sind keine Performance-Messungen.
- Neue Tests oder Phone-Checks wurden für diese Recherche nicht ausgeführt.

Die Vorschläge bleiben innerhalb der bestehenden einfachen Architektur:
native Views/Canvas, eine Activity, Repository, Executors und Handler. Es ist
kein Wechsel zu einem Map-SDK, Compose, ViewModels oder Coroutines nötig.
Es gelten weiterhin die Projektregeln aus [AGENTS.md](../../AGENTS.md).

## 2. Vorhandene Stärken

Der Radar-Tab bietet bereits Verschieben, Pinch-/Doppeltipp-Zoom, gespeicherte
Kamera, Scrubbing und einen einzelnen Play/Pause-Wiedergabezyklus. Zentrieren
ist als Accessibility-Aktion vorhanden. Die feste Zeitachse reicht von einer
Stunde vor dem Ausgabezeitpunkt bis zwei Stunden danach; vollständig sind
das zwölf historische Beobachtungen und 25 Frames des aktuellen Laufs.

Die Datenverarbeitung hat wichtige Eigenschaften, die erhalten bleiben sollen:

- Bedingte HTTP-Anfragen vermeiden erneute Archivdownloads bei unverändertem
  DWD-Lauf; passende Frames werden vor erneutem HDF5-Decoding wiederverwendet.
- Fertige Bilder werden während der Verarbeitung veröffentlicht. Die Anzeige
  muss nicht auf die vollständige Historie warten.
- Die Historie verwendet echte Beobachtungen. Alte Prognosen werden nicht
  nachträglich als Beobachtung ausgegeben.
- Historische Downloads lesen nur das erste, beobachtete Archivmitglied.
- Gespeicherte PNGs werden erst bei Bedarf als Bitmap decodiert.
- Bei einem fehlgeschlagenen Bildwechsel bleibt das letzte sichtbare Bild
  samt zugehöriger Zeit erhalten.
- Alter, Offline-Zustand und historische Lücken werden berücksichtigt;
  erfolgreiche Downloadzeit und Laden aus dem Cache sind getrennt.
- Projektion, Einheiten, Zeitzuordnung und versionierter PNG-Cache haben
  gezielte Validierung und bestehende Regressionstests.

## 3. Reaktionszeit und unnötige Arbeit

Die Prioritäten sind eine erste Einschätzung und nach einer kurzen Messung auf
dem üblichen Telefon gegebenenfalls anzupassen.

| Priorität | Codebefund | Mögliche Auswirkung | Ansatz |
| --- | --- | --- | --- |
| Hoch | Scrubbing reiht Bild-Decodes in einen Single-Thread-Executor ein. Der Token wird erst nach dem Decodieren geprüft. Wiederholte Bewegungen innerhalb desselben Zeitschritts können erneut Arbeit einreihen. | Die letzte Auswahl wartet hinter bereits überholten Bildern; unnötige CPU-Arbeit und Bitmap-Allokationen. | Aufträge zusammenfassen, gleiche laufende Auswahl erkennen und veraltete Aufträge vor dem Decode verwerfen. Nur die aktuelle Auswahl und gezielte Vorbereitung berücksichtigen. |
| Hoch | Jeder gelesene Downloadblock von bis zu 8 KiB meldet Fortschritt. Jeder Callback führt über `changed()` zu `render()`, einschließlich Karten-Invalidierung, Formatierung und Bindung des geschlossenen Radar-Detailpanels. | Unnötige UI-Arbeit während eines Downloads; tatsächliche Draws können vom Framework zusammengefasst werden, die Callback-Arbeit bleibt trotzdem. | Fortschritt zeitlich oder nach sichtbarer Änderung zusammenfassen. Nur betroffene und sichtbare Ansichten aktualisieren. |
| Hoch | Eine vorhandene, ältere Festplatten-Kachel wird erst nach Netzvalidierung oder deren Fehlschlag zurückgegeben. | Zunächst leere Karte trotz brauchbarem gespeichertem Bild, besonders bei langsamer Verbindung. | Gespeicherte Kachel sofort zeigen und anschließend bedingt aktualisieren. Empfangs-/Validierungszeit nicht mit Zugriffszeit vermischen. |
| Mittel bis hoch | Beim Verschieben/Zoomen bleiben bereits angefragte Kacheln in der Warteschlange, auch wenn sie inzwischen außerhalb des sichtbaren Ausschnitts liegen. | Neue sichtbare Kacheln warten hinter überholten Anfragen; vermeidbarer Datenverbrauch. | Sichtbare Kacheln priorisieren; vor Arbeitsbeginn prüfen, ob ein Auftrag noch gebraucht wird. Laufende Anfragen sinnvoll begrenzen. |
| Mittel | Beim Zoom fehlt eine vorläufige Darstellung aus bereits vorhandenen gröberen Kacheln. | Leere Flächen, bis die neue Zoomstufe geladen ist. | Bereits geladene Kacheln vorübergehend skaliert darstellen; dafür keine zusätzlichen unsichtbaren OSM-Kacheln herunterladen. |
| Mittel | Das erste neue Radarbild wartet auf den vollständigen TAR-Download und das Einsammeln aller relevanten Archivmitglieder. | Zusätzliche Wartezeit bis zum ersten sichtbaren neuen Bild. | Das erste beobachtete Archivmitglied möglichst bereits beim Einlesen verarbeiten und veröffentlichen. |
| Mittel | Verarbeitungskette: HDF5 → Farbarray → Bitmap → PNG → Datei → erneutes PNG-Decoding für die Anzeige. | Temporäre Allokationen, Kompressionsarbeit, I/O und zusätzliche Latenz. | Den gerade erzeugten, benötigten Frame direkt für die Anzeige bereitstellen. Persistenz und sichtbares Ergebnis koordinieren; nicht alle Frames dauerhaft im RAM halten. |
| Mittel | Nach jeder abgeschlossenen Kartengeste wird die Kamera synchron mit `SharedPreferences.commit()` gespeichert. | Möglicher kurzer Hänger beim Loslassen. | Speicherung aus dem unmittelbaren Interaktionspfad nehmen, ohne die bisherige zuverlässige Wiederherstellung aufzugeben. |

Relevante Implementierung:

- [MainActivity.kt](../../app/src/main/java/app/drizziq/MainActivity.kt):
  `requestFrame`, `renderRadar`, `render` und `advance`.
- [RadarMap.kt](../../app/src/main/java/app/drizziq/RadarMap.kt):
  `request`, `onDraw`, `saveViewport` und `onTouchEvent`.
- [Repository.kt](../../app/src/main/java/app/drizziq/Repository.kt):
  `Http.read`, `refreshRadar` und der HDF5-/PNG-Verarbeitungspfad.
- [RadarRefresh.kt](../../app/src/main/java/app/drizziq/RadarRefresh.kt):
  `load` und inkrementelle Veröffentlichung.
- [TileCache.kt](../../app/src/main/java/app/drizziq/TileCache.kt):
  `TileFetcher.load`.

Die verwendeten Executor-Typen besitzen unbegrenzte Warteschlangen. Generation-
Tokens schützen die Anzeige vor veralteten Ergebnissen, verhindern aber nicht
automatisch deren Berechnung. Siehe die
[Android-Dokumentation zu Executors](https://developer.android.com/reference/java/util/concurrent/Executors).

### Wiedergabe und Arbeitsspeicher

Der Bitmap-Cache enthält drei Radarframes. Bei einem vollständigen fortlaufenden
Durchlauf mit bis zu 37 Bildern muss damit praktisch jedes Bild erneut decodiert
werden. Der folgende Schritt wird erst 700 ms nach erfolgreicher Anzeige
geplant, am Ende nach 1400 ms. Die Decode-Zeit verlängert den jeweiligen Abstand.
Es gibt kein gezieltes Vorbereiten des nächsten Bildes.

Ein Radarbild mit 1100 × 1200 Pixeln benötigt als ARGB-Bitmap rechnerisch etwa
5 MiB. Alle 37 Bilder würden ungefähr 186 MiB benötigen, zuzüglich temporärer
Daten und Grafikressourcen. Deshalb einen kleinen Puffer mit gezielter
Vorbereitung untersuchen, statt den ganzen Film zu decodieren. Die bewussten
Fünf-Minuten-Zeitschritte sind von stockender UI oder Decode-Verzögerungen zu
unterscheiden. Künstliche Zwischenbilder würden zusätzliche meteorologische
Aussagen suggerieren und gehören nicht zu dieser Empfehlung.

## 4. Ressourcenverbrauch und Cache-Fehlerfälle

| Befund | Konsequenz und Vorschlag |
| --- | --- |
| Radar wird bereits beim App-Start angestoßen und über die gemeinsame Aktualisierung ungefähr alle fünf Minuten geprüft, unabhängig vom sichtbaren Tab. | Vorbereitete Daten können den späteren Tab-Wechsel beschleunigen, verursachen aber Arbeit ohne Radar-Nutzung. Cache-Anzeige, Prüfung und vollständige Verarbeitung nach tatsächlichem Bedarf staffeln. |
| Hintergrundwechsel beendet bereits laufende Radar-/Kachelarbeit nicht gezielt. | Wiedergabe und periodischer Tick werden pausiert, laufende Downloads und Verarbeitung können weiterarbeiten. Einen klaren Umgang mit laufender Arbeit festlegen; keinen neuen Hintergrunddienst einführen. |
| Schon ein Festplatten-Kacheltreffer schreibt den gesamten Zugriffsindex atomar mit `fsync`. Neue Kacheln erzeugen weitere Validator-Schreibvorgänge und Verzeichnisscans. | Die Arbeit läuft im Hintergrund, beschäftigt aber Kachel-Worker und Speicher. Zugriffszeiten gesammelt speichern; notwendige atomare Daten-/Validator-Schreibvorgänge erhalten. |
| Ein einziges fehlerhaftes Radar-PNG verwirft beim Laden die ganze gespeicherte Sequenz. | Intakte Frames stehen zunächst ebenfalls nicht bereit. Gültige Frames behalten, einzelne Lücken markieren und gezielt reparieren. |
| Ein fehlgeschlagener neuer Manifest-Schreibvorgang wird toleriert; die anschließende Bereinigung richtet sich trotzdem nach der neuen Sequenz. | Ältere Dateien können gelöscht werden, obwohl das letzte gültige Manifest sie noch referenziert. Erst nach erfolgreichem Manifestwechsel entsprechend bereinigen; den alten gültigen Stand schützen. |
| Es gibt kein eigenes Byte-Budget für Radar-PNGs; alte Bilder werden überwiegend nach erfolgreichem Refresh bereinigt. | Wiederholte teilweise fehlgeschlagene Läufe können zusätzliche Dateien hinterlassen. Radarbudget und Aufräumen verwaister Dateien festlegen, ohne brauchbare gespeicherte Daten bei Fehlern zu verlieren. |

Quellen im Projekt:
[MainActivity.kt](../../app/src/main/java/app/drizziq/MainActivity.kt),
[Repository.kt](../../app/src/main/java/app/drizziq/Repository.kt),
[CachePolicy.kt](../../app/src/main/java/app/drizziq/CachePolicy.kt),
[TileCache.kt](../../app/src/main/java/app/drizziq/TileCache.kt) und
[RadarCache.kt](../../app/src/main/java/app/drizziq/RadarCache.kt).

Der Manifest-/Bereinigungsfehler ist ein konkreter Fehlerpfad aus dem Code-Review;
er wurde in dieser Recherche nicht auf dem Gerät reproduziert. Vor Umsetzung
einen kleinen Regressionstest für fehlgeschlagenes Speichern und Erhalt des
vorherigen Cache-Stands schreiben.

## 5. Funktionen mit hohem Alltagsnutzen

Die Auswahl orientiert sich an passenden Konkurrenzfunktionen und dem schlanken
Projektumfang. Sie ist keine vollständige Marktübersicht und keine Zusage,
alle aufgeführten Funktionen einzubauen.

| Funktion | Nutzen und heutige Lücke | Erste Ausbaustufe |
| --- | --- | --- |
| Regenverlauf am ausgewählten Ort | Eine Karte verlangt räumliche Interpretation; eine Kurve beantwortet direkt, wann Regen ungefähr beginnt oder endet. Buienalarm bietet eine zweistündige Niederschlagskurve. | Kurve aus vorhandenen RV-Daten unter der Karte, Intensität und vorsichtige Beginn-/Ende-Schätzung in Fünf-Minuten-Schritten. |
| Amtliche Wetterwarnungen | Warnflächen, Gültigkeit und vollständiger Warntext fehlen. WarnWetter verbindet Warnungen mit Orten und Karten. | CAP-Warnungen im Vordergrund abrufen, für den gewählten Ort und auf der Karte anzeigen. |
| Beobachtung und Prognose sichtbar unterscheiden | Aktuell ist die Unterscheidung vor allem in Accessibility-Texten enthalten; sichtbar stehen überwiegend Uhrzeit und einheitliche Zeitachse. | Beschriftungen wie „Beobachtet“ / „Prognose +35 min“, erkennbare Grenze, beschriftete Zeitpunkte und „Jetzt“-Sprung. |
| Direkte Kartenbedienung | Zentrieren ist als Accessibility-Aktion vorhanden, aber ohne sichtbaren Knopf. | Sichtbares Zentrieren, Maßstab, optional Vollbild und regelbare Radar-Deckkraft. Zoomtasten bei tatsächlichem Bedarf. |
| Bessere Wiedergabesteuerung | Feste Geschwindigkeit und Decode-Abhängigkeit begrenzen die Bedienung. | Einstellbare Geschwindigkeit und bei Bedarf einzelne Schritte vor/zurück; weiterhin nur eine Wiedergabeschleife. |
| Kleine Farbskala | Farbklassen sind ohne Legende schwer quantitativ einzuordnen. | Nach den jüngsten Vereinfachungen vorzugsweise im Detailpanel statt als dauerhaft große Kartenebene. |
| Niederschlagsart | Regenintensität allein unterscheidet Schnee, gefrierenden Regen und Hagel nicht. WarnWetter bietet entsprechende Kategorien. | Aktuelle HymecNG-Klassifikation mit eigenem Zeitstempel und klaren unbekannten Zuständen. |
| Gewitterzugbahnen | Starke Niederschläge allein erklären die Entwicklung einer Gewitterzelle nicht. | KONRAD3D-Umrisse, Zugrichtung und ausgewählte Attribute; automatische Hinweise von amtlichen Warnungen unterscheiden. |

Vergleichsquellen:
[Buienalarm](https://prod.buienalarm.nl/),
[WarnWetter-Funktionsübersicht](https://www.warnwetterapp.de/index.html) und
[Buienradar-FAQ zu Radar und Niederschlagsgrafik](https://www.buienradar.nl/overbuienradar/faq-app).

### Ortskurve und Einheiten

Die vorhandenen RV-Daten reichen grundsätzlich für eine lokale Kurzfristkurve.
Aktuell wird beim Decodieren allerdings nur ein Farbarray und anschließend ein
klassifiziertes PNG behalten. Genaue Mengen lassen sich daraus nicht zuverlässig
zurückgewinnen. Numerische Werte müssen beim ursprünglichen Decode zusätzlich
für den gewählten Ort bzw. einen begrenzten Auswertungsbedarf erhalten bleiben.
Keine zweite vollständige Rasterhistorie ohne nachgewiesenen Bedarf einführen.

RV enthält Niederschlagsmengen in **mm pro fünf Minuten**. Eine Darstellung als
äquivalente Intensität in mm/h erfordert die entsprechende Umrechnung:
0,1 mm in fünf Minuten entspricht 1,2 mm/h. Das ist keine gemessene
Stundensumme. Fehlende Intervalle dürfen nicht als trocken zählen; außerhalb
der Abdeckung bleibt der Wert unbekannt. Regenbeginn und -ende sind
radarbasierte Schätzungen, keine minutengenauen Versprechen. Siehe die
[DWD-RV-Produktbeschreibung](https://www.dwd.de/DE/leistungen/radarprodukte/radarkomposit_rv_hdf5.pdf?__blob=publicationFile&v=3).

### Spätere Optionen

Regenalarme und Widgets sind interessant, aber zuverlässige Hintergrundalarme
bringen zusätzliche Anforderungen an Akkuverbrauch, Aktualität und
Android-Hintergrundbetrieb mit. Zunächst die Funktionen bei geöffneter App
verbessern. Dafür ist weder kontinuierliches Standorttracking noch ein eigener
Backend-/Push-Dienst nötig.

Satellit und Temperaturkarten wurden kurz vor dieser Recherche bewusst wieder
entfernt. Ihre Wiedereinführung ist nicht Teil der ersten Empfehlung. Neue
Funktionen sollen den Niederschlagsradar ergänzen und nicht die entfernte
Produkt-/WMS-Komplexität ungeprüft zurückbringen.

## 6. Geeignete Datenquellen

### DWD: bevorzugte Ergänzungen

Die folgenden Produkte werden öffentlich über DWD Open Data bereitgestellt,
ohne Account oder API-Schlüssel. DWD-Quellenangabe, CC-BY-4.0-Hinweise und die
bestehenden gebündelten Notices bei einer Integration entsprechend ergänzen.

| Quelle | Inhalt und möglicher Nutzen | Grenzen und Empfehlung |
| --- | --- | --- |
| [CAP-Warnungen](https://opendata.dwd.de/weather/alerts/cap/) | Warnereignis, Warnstufe, Zeitbezug, Warntext und betroffene Flächen/Gebiete. | Erste zusätzliche Quelle. Mit vollständigen Statusständen beginnen. Polygon- und Gebietszuordnung, Aktualisierungen, Aufhebungen und Gültigkeit korrekt behandeln. |
| [HymecNG](https://opendata.dwd.de/weather/radar/composite/hymecng/) | Niederschlagsart in 2 m Höhe über Grund; 1-km-Raster, alle fünf Minuten, elf Klassen einschließlich Regen, Schnee, Schneeregen, gefrierendem Niederschlag und Hagel. | Sehr passend für den Winter. Aktuelle Klassifikation ist keine zweistündige Prognose der Niederschlagsart. Nicht einfach über alle zukünftigen RV-Frames legen. |
| [KONRAD3D](https://opendata.dwd.de/weather/radar/konrad3d/) | Erkennung, Verfolgung und Nowcasting konvektiver Zellen; Geometrie, Schweregrad, Hagel-/Starkregen-/Böenhinweise und weitere Attribute. XML alle fünf Minuten, einschließlich Unsicherheitsinformationen. | Gute spätere Gewittererweiterung. Zeitbezug und automatische Zellmerkmale sichtbar machen; keine Gleichsetzung mit amtlichen CAP-Warnungen oder einzelnen Blitzeinschlägen. |
| [RADOLAN RW](https://opendata.dwd.de/weather/radar/radolan/rw/) | An Bodenmessungen angepasste Niederschlagsstundensummen; Grundlage längerer Mengenrückblicke. | Für „Wie viel ist gefallen?“ sinnvoll. Durch zeitlichen Bezug und Bereitstellungsverzögerung kein Ersatz für den unmittelbaren RV-Nowcast. Längere Summen nur mit vollständigen Intervallen. |
| [HX-Komposit](https://opendata.dwd.de/weather/radar/composite/hx/) | Aktuelle Radarreflektivität mit 250-m-Raster, alle fünf Minuten; 4800 × 4400 Pixel. | Optionale spätere Detailansicht. Gegenüber RV viermal feinere Rasterweite und 16-mal so viele Pixel. Reflektivität ist keine direkte Regenmenge; Aufwand und Speicher sprechen gegen die erste Ausbaustufe. |

Offizielle Produkt-/Formatquellen:

- [DWD CAP-Profil](https://www.dwd.de/DE/leistungen/opendata/help/warnungen/cap_dwd_profile_de_pdf_2_1_14.pdf?__blob=publicationFile&v=3).
- [HymecNG-Produktbeschreibung](https://www.dwd.de/DE/leistungen/radarprodukte/radarkomposit_hymecng_hdf5.pdf?__blob=publicationFile&v=2).
- [KONRAD3D-Kurzbeschreibung](https://www.dwd.de/DE/leistungen/radarprodukte/konrad3D_kurzbeschreibung.pdf?__blob=publicationFile&v=2).
- [RADOLAN-Verfahrensbeschreibung](https://www.dwd.de/DE/leistungen/radolan/radolan_info/radolan_bericht_stetisierungsverfahren_2022_pdf.pdf?__blob=publicationFile&v=2).
- [HX-Produktbeschreibung](https://www.dwd.de/DE/leistungen/radarprodukte/radarkomposit_hx_hdf5.pdf?__blob=publicationFile&v=2).

Einige PDFs auf `www.dwd.de` lieferten beim direkten Abruf HTTP 403; ihre
relevanten Beschreibungen waren in den Suchauszügen der offiziellen Dokumente
verfügbar. Produktverzeichnisse wurden ebenfalls geprüft. Das ersetzt keine
vollständige Formatprüfung mit echten Fixtures vor einer Integration.

### RainViewer: mögliche Ergänzung außerhalb der DWD-Abdeckung

Die öffentliche API ist laut Anbieter für persönliche Nutzung kostenlos und
ohne Account/API-Schlüssel erreichbar. Sie bietet internationale Radarkacheln
und verlangt sichtbare verlinkte Attribution. Die aktuelle Detaildokumentation
nennt zwei Stunden Vergangenheit in Zehn-Minuten-Schritten und maximal
Zoomstufe 7. Die Umstellungsseite dokumentiert die Einstellung des freien
Nowcasts zum 2026-01-01.

Die Anbieterübersicht enthält dazu widersprüchliche Angaben, unter anderem zu
Nowcast und Limits. Bei einer konkreten Umsetzung Dokumentation und tatsächliche
Antworten erneut prüfen und konservative Anfragelimits verwenden. Als zusätzliche
Beobachtungsabdeckung außerhalb Deutschlands denkbar; als Ersatz für das
vorhandene DWD-Radar mit Vorhersage unattraktiv.

Quellen: [API-Übersicht und Nutzungsbedingungen](https://www.rainviewer.com/api.html),
[Weather Maps API](https://www.rainviewer.com/api/weather-maps-api.html),
[API-Umstellung](https://www.rainviewer.com/api/transition-faq.html).

### Blitzortung: vorerst zurückstellen

Öffentliche Blitzkarten sind kein frei nutzbarer Rohdatenzugang für unsere
App. Der Anbieter beschränkt den Datenzugriff auf Teilnehmer bzw. ausdrücklich
autorisierte Nutzer und schränkt Nutzungszwecke ein, insbesondere Warnsysteme.
Deshalb keine direkte Integration oder Übernahme aus fremden Karten als
naheliegende kostenlose API einplanen.

Quellen: [Blitzortung-Compendium](https://www.blitzortung.org/en/compendium.php),
[Limaps-Anbieterbedingungen](https://limaps.org/index.html).

## 7. Empfohlene Reihenfolge

1. **Kurze Messung auf dem üblichen Telefon:** Kaltstart, Cache-Start, schnelles
   Scrubben, Kartenzoom und Refresh während der Bedienung erfassen.
2. **Reaktionszeit verbessern:** Decode-Aufträge zusammenfassen,
   Fortschrittsmeldungen bündeln, gespeicherte Kacheln sofort zeigen und
   sichtbare Kartenanfragen priorisieren. Danach dieselben Abläufe vergleichen.
3. **Cache-Fehlerfälle absichern:** Bereinigung an erfolgreichen Manifestwechsel
   binden, gültige Frames trotz einzelner Schäden erhalten, Radarbudget und
   verwaiste Dateien berücksichtigen. Regressionsfälle vor dem Fix testen.
4. **Bedienung ergänzen:** „Jetzt“, sichtbares Zentrieren, Beobachtung/Prognose,
   beschriftete Zeitleiste und Abspielgeschwindigkeit.
5. **Ortskurve und CAP-Warnungen ergänzen:** Zunächst bei geöffneter App und
   mit begrenztem Daten-/Speicherbedarf.
6. **HymecNG und KONRAD3D prüfen:** Jeweils ein eigener kleiner Integrationsschritt
   mit echten Datenfixtures und explizitem Zeit-/Abdeckungsbezug.
7. **Später nach Bedarf:** RADOLAN-Rückblicke, internationale Beobachtungen,
   Widgets oder Regenalarme. HX erst nach Nachweis eines konkreten Nutzens.

## 8. Schlanke Messung und Verifikation

Keine neue Benchmark-Infrastruktur oder Gerätematrix einführen. Ein kurzer
Vergleich auf dem üblichen Telefon reicht zunächst. Reale Orte, Favoriten,
Kamera und Berechtigungen erhalten; keinen Cache ungefragt löschen und keine
Funkverbindungen für diese Messung umschalten.

| Messgröße | Fragestellung |
| --- | --- |
| Zeit bis zum ersten sichtbaren Radarbild | Wie unterscheiden sich Start mit vorhandenen Daten und echter Kaltstart? |
| Zeit von letzter Scrub-Bewegung bis zum passenden Bild | Wartet die endgültige Auswahl hinter überholter Arbeit? |
| Ausgelassene UI-Frames beim Scrubben, Zoom und Download | Ist vor allem die UI beschäftigt oder wartet nur die Bildverarbeitung? |
| Zeit bis zur nutzbaren Basiskarte | Helfen Cache-Sofortanzeige und Kachelpriorisierung? |
| Speicher und temporäre Allokationen | Welche Kosten entstehen beim HDF5-Decoding, PNG-Schreiben und wiederholten Abspielen? |
| Anfragen und übertragene Bytes | Wie viel Arbeit entsteht ohne sichtbaren Radar-Tab oder für überholte Kartenausschnitte? |

Bei Implementierungsänderungen nur die betroffenen JVM-/Phone-Phasen ausführen.
Für UI-Änderungen den geänderten Bildschirm kurz auf dem üblichen Telefon
ansehen. Vor einem Release gelten der einmalige lokale Gate-Lauf und kurze
Phone-Smoke aus AGENTS.md. Reine Dokumentationsänderungen brauchen keine Tests.
