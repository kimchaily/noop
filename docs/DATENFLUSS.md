# Datenfluss — Rohwerte, Ableitungen, Scores

Wer diese Datei liest, will wissen: welche Rohströme liefert das Armband, was wird daraus wie
berechnet, wo liegt das Ergebnis — und **welche Ebene fasst eine Neuberechnung an**. Alle Zahlen
hier sind aus dem Code gelesen, nicht aus der Erinnerung; die Quelldatei steht jeweils dabei.

Ergänzt `DATA_MODEL.md` (Schema) und `ANALYTICS.md` (Formeln) um die Frage, die keine der beiden
beantwortet: was passiert mit einem Messwert zwischen Sensor und Ring.

## Drei Ebenen, eine Richtung

Daten fließen ausschließlich von oben nach unten. Keine Ableitung schreibt je in die Ebene über
ihr zurück.

| | Ebene | Inhalt | Wer schreibt | Neuberechnung? |
|---|---|---|---|---|
| 1 | **Rohmesswerte** | `hrSample` `ppgHrSample` `rrInterval` `respSample` `skinTempSample` `spo2Sample` `stepSample` `gravitySample` `sleepStateSample` `event` `battery` | Offload vom Armband · Import | **nie** — nur anhängen |
| 2 | **Tageswerte** | `dailyMetric` `sleepSession` `metricSeries` | der Analyse-Durchlauf | **ja** — löschen und neu schreiben |
| 3 | **Scores** | `recovery` (Charge) · `strain` (Effort) · `sleep_performance` (Rest) | der Analyse-Durchlauf | **ja** — Spalten in Ebene 2 |

**Die Grenze:** Ebene 1 kennt genau zwei Schreibwege, Offload und Import, und beide hängen an. Der
Analyse-Durchlauf liest sie ausschließlich. Was er löscht und neu schreibt, liegt unter einer
eigenen Geräte-ID (siehe unten).

## Ebene 1 — was das Armband liefert

Alle Tabellen in `android/app/src/main/java/com/noop/data/Entities.kt`, geschlüsselt nach
`(deviceId, ts)`. Ein doppelt geliefertes Sample überschreibt nichts, es fällt am
Primärschlüssel weg.

| Tabelle | Inhalt | Einheit | Wird zu |
|---|---|---|---|
| `hrSample` | Herzfrequenz, ein Wert je Sekunde | bpm | Ruhepuls · Effort · Kalorien · Schlaferkennung · Live-Puls |
| `ppgHrSample` | aus der PPG-Kurve per Autokorrelation **rekonstruierte** HF; getrennte Tabelle, damit eine Schätzung nie mit einer Messung verwechselt wird | bpm + `conf` 0…1 | füllt nur Sekunden ohne eigenen Armband-Puls (WHOOP 5/MG) |
| `rrInterval` | Schlag-zu-Schlag-Abstände, mehrere pro Sekunde möglich | ms | **HRV (RMSSD)** · Atemfrequenz |
| `respSample` | Atemsensor, roher ADC | raw | — (WHOOP 5 liefert nichts Brauchbares mehr) |
| `skinTempSample` | Hauttemperatur-Register; Skala hängt an der Familie (5/MG: Hundertstelgrad, 4.0: roher ADC) | raw | nächtliches Mittel → `skinTempDevC` |
| `spo2Sample` | Rot- und Infrarotkanal | raw ADC | **nichts** — siehe Lücken |
| `stepSample` | *kumulativer* u16-Schrittzähler (läuft über) + Aktivitätsklasse | 0…65535 · 0 still/1 gehen/2 laufen | Tagesschritte |
| `gravitySample` | Schwerkraftvektor → Lage und Bewegung | x/y/z in g | Hypnogramm · Workout-Erkennung · Unruhe je Epoche |
| `sleepStateSample` | der **eigene** Schlafzustand des Armbands (Nibble); bewusst nur Zweitmeinung, nie Übersteuerung | 0 wach/1 still/2 schlafend/3 auf | Bestätigung eines Grenzfalls beim Wiedereinschlafen |
| `event` | Armband-Ereignisse als `kind` + JSON, u. a. Handgelenk an/ab | text | Ab-Handgelenk-Intervalle → abgelegte Uhr zählt nicht als Schlaf |
| `battery` | Ladestand, Spannung, Ladezustand | % · mV · bool | Batterieanzeige und -warnung |

## Ebene 2 — was pro Tag gerechnet wird

Eine Zeile je Tag in `dailyMetric`, `(deviceId, day)` mit `day` als **lokalem** Kalendertag.
Jede Spalte ist einzeln nullable: fehlt die Grundlage, bleibt der Wert leer statt erfunden.

Berechnet in `AnalyticsEngine.analyzeDay`, orchestriert von
`IntelligenceEngine.analyzeRecentOnCpu`. Das Nachtfenster reicht von 30 h vor dem Tag bis zur
folgenden Mitternacht; die addierenden Tagessummen (Schritte, Kalorien) lesen separat exakt den
Kalendertag.

| Spalte | Aus | Wie |
|---|---|---|
| `avgHrv` | `rrInterval` | RMSSD (Task Force 1996) je Schlafblock, dann über alle Blöcke des Tages **nach Liegedauer gewichtet** gemittelt — der Hauptschlaf dominiert von selbst |
| `restingHr` | `hrSample` | der **niedrigste** Ruhepuls über alle Blöcke des Tages, nicht deren Mittel |
| `respRateBpm` | `rrInterval` | RSA-Schätzung je Block, Tageswert = Median der endlichen Schätzungen. **Schätzung**, kein Sensorwert |
| `skinTempDevC` | `skinTempSample` | getragen-geprüftes Nachtmittel, dann die *Abweichung* gegen die persönliche Baseline (zwei Durchläufe) |
| `totalSleepMin` `deepMin` `remMin` `lightMin` `efficiency` `disturbances` | `gravitySample` `hrSample` `rrInterval` `sleepStateSample` | Hypnogramm des Stagers auf 30-s-Raster; `event` verwirft abgelegte Nächte |
| `steps` | `stepSample` | Summe positiver Zähler-Differenzen über den Kalendertag, mit u16-Überlauf. **Näherung** — nicht gegen die Hersteller-App verifiziert |
| `activeKcalEst` | `hrSample` + Profil | Keytel (aktiv) + Harris–Benedict (Grundumsatz). **Schätzung aus dem Puls** |
| `exerciseCount` | `gravitySample` + `hrSample` | erkannte Trainingsblöcke über das volle Kalendertagfenster (Lauf um 17 Uhr erscheint am selben Tag) |
| `spo2Pct` | — | wird auf dem Gerät **nie** berechnet, immer `null`. Ein Wert steht hier nur aus einem Import |

Daneben, gleiche Ebene:

- **`sleepSession`** — je Nacht die Phasensegmente (`stagesJSON`), Bewegung je Epoche
  (`motionJSON`), Armband-Zustand je Epoche (`sleepStateJSON`). `userEdited` und
  `startTsAdjusted` halten eine von Hand korrigierte Bett-/Aufwachzeit; eine Neuberechnung
  **bewahrt** sie, statt die erkannten Grenzen darüberzuschreiben.
- **`metricSeries`** — Langformat `(deviceId, day, key) → value` mit den Schlüsseln `charge`,
  `strain`, `sleep_performance`, `rhr`, `hrv`, `steps`, `calories_in`, `weight`,
  `sleep_total_min`. Die Form, in der Trends und Vergleiche lesen.

## Ebene 3 — die drei Ringe

Alle drei sind **reine Funktionen**: gleiche Tageswerte plus gleiche Baseline ergeben immer die
gleiche Zahl. Fehlt ein Term, fällt er heraus und die verbleibenden Gewichte werden neu normiert
— nie wird ein fehlender Wert durch einen erfundenen ersetzt.

### Charge (`recovery`) — `RecoveryScorer.kt`

| Term | Gewicht | Richtung | z |
|---|---:|---|---|
| `avgHrv` | 0,55 | höher ist besser | `(x − μ) / (1,253·s)` |
| `restingHr` | 0,20 | niedriger ist besser | `(μ − x) / (1,253·s)` |
| Rest ÷ 100 | 0,15 | höher ist besser | `(x − 0,85) / 0,12` |
| `respRateBpm` | 0,05 | niedriger ist besser | `(μ − x) / (1,253·s)` |
| `skinTempDevC` | 0,05 | Abweichung in **beide** Richtungen kostet | `−|x| / 1,0` |

```
z      = Σ(z_i · w_i) / Σ w_i
Charge = 100 / (1 + exp(−1,6 · (z + 0,20)))      # z = 0 → 58
```

Der Rest-Term braucht keine Baseline (auf 0,85 zentriert), der Hauttemperatur-Term auch nicht
(er *ist* bereits eine Abweichung). Ist die HRV-Baseline nicht brauchbar, wird **gar nicht
bewertet** — `null` statt einer erfundenen Zahl.

### Effort (`strain`) — `StrainScorer.kt`

Herzfrequenzreserve nach Karvonen → Zonen → aufsummierte TRIMP-Last (Edwards bzw. Banister) →
`100 · ln(TRIMP + 1) / ln(D)`. Integriert über den vollen Kalendertag, damit ein Abendtraining am
selben Tag zählt und der Vorabend nicht hineinblutet. HFmax nach Tanaka 2001 (`208 − 0,7·Alter`),
sofern nicht von Hand gesetzt.

### Rest (`sleep_performance`) — `RestScorer` in `AnalyticsEngine.kt`

| Komponente | Gewicht | Woraus |
|---|---:|---|
| Dauer gegen persönlichen Bedarf | 0,50 | `totalSleepMin` |
| Effizienz | 0,20 | `efficiency` |
| Erholsam: (Tief + REM) / Schlaf | 0,20 | `deepMin + remMin` |
| Konsistenz der Schlafenszeit | 0,10 | Zeitpunkte der Vornächte |

Zielanteil für den Erholsam-Term: 0,50 (≈ 20 % Tief + 25–30 % REM). Darüber volle Gutschrift,
darunter linear.

### Die Baseline — `Baselines.kt`

Ein winsorisierter exponentieller Mittelwert über die **vorangegangenen** Nächte; ein Tag wird nie
gegen sich selbst oder gegen spätere Nächte gemessen. Ausreißer werden gestutzt statt verworfen,
damit eine einzelne schlechte Nacht die Baseline nicht kippt.

| Konstante | Wert | Bedeutung |
|---|---|---|
| `halfLifeB` | 14 Nächte | Halbwertszeit des Zentrums |
| `halfLifeS` | 21 Nächte | Halbwertszeit der Streuung, träger als das Zentrum |
| `minNightsSeed` | 4 | ab hier wird überhaupt bewertet |
| `minNightsTrust` | 14 | ab hier belastbar, davor „vorläufig" |
| `earlyAdaptNights` | 8 | solange jung: Halbwertszeit 3 statt 14, Streuung ×2,5 aufgeweitet |
| `staleDays` | 14 | so lange ohne neue Nacht ⇒ veraltet, der Term fällt heraus |
| `winsorK` | 3,0 | jenseits davon auf die Grenze gestutzt |
| `hardOutlierK` | 5,0 | jenseits davon ganz verworfen |
| `floorSpread` | HRV 5,0 ms · RHR 2,0 bpm | Mindeststreuung |

Identisch zur Swift-Fassung; Parität ist durch Tests abgesichert.

## Speicherorte — drei Geräte-IDs

Die `deviceId` ist der eigentliche Schutzmechanismus: berechnete Werte landen nie unter der ID,
unter der die Rohwerte liegen. Der Suffix entsteht in `WhoopRepository.computedDeviceId`:
`"$deviceId-noop"`.

| Geräte-ID | Was dort liegt | Neuberechnung? |
|---|---|---|
| `my-whoop`, `whoop-C5:…` | Rohströme **und** aus einem WHOOP-Export importierte Tageswerte | **nie** |
| `…-noop` | alles auf dem Gerät Berechnete | **ja** |
| `apple-health`, `health-connect`, … | importierte Fremdwerte | nie — Import gewinnt über Berechnung |

Ein Armband mit zwei Identitäten (nach einem Neu-Koppeln) wird beim **Lesen** vereinigt, beim
Schreiben nie.

### Was ein Durchlauf konkret tut

Er ermittelt den **frühesten** Tag, dessen Eingangsdaten sich geändert haben, und schreibt von
dort bis heute neu — nicht nur den einen Tag, denn jeder Tag wird gegen die Nächte vor ihm
bewertet. Löschen und Neuschreiben liegen in **einer** Transaktion, ein Absturz dazwischen kann
also keine Lücke hinterlassen. Erst *nachdem* die Zeilen geschrieben sind, werden die Prüfsummen
der Tage gespeichert — sonst könnte ein abgebrochener Durchlauf Tage als aktuell markieren, deren
Werte nie ankamen.

## Anzeige

| Bildschirm | Zeigt | Ebene |
|---|---|---|
| Heute | die drei Ringe, Vitalwerte der Nacht, Live-Puls | 3 · 2 · 1 |
| Gesundheit | Karten je Vitalwert mit Bandbreite, Delta, Sparkline | 2 |
| Trends · Erkunden | beliebige Tageswertreihe über die Zeit | 2 · 3 |
| Vergleichen | zwei Zeiträume derselben Reihe gegeneinander | 2 · 3 |
| Schlaf | Hypnogramm, Blöcke, Handkorrektur von Bett-/Aufwachzeit | 2 |
| Ganztagsdiagramm | die Rohkurven selbst — Puls, Schritte, Hauttemperatur, SpO₂-Rohkanäle | **1** |
| Testzentrum | die Rechenspur je Nacht: welche Baseline, welcher z-Wert, welches Gewicht | alle |

## Ehrliche Lücken

Vier Stellen, an denen der Weg nicht so vollständig ist, wie die Oberfläche vermuten lässt.

- **SpO₂ wird gemessen, aber nie ausgewertet.** Rot- und Infrarotkanal landen vollständig in
  `spo2Sample`, doch `AnalyticsEngine` schreibt `spo2Pct` unbedingt als `null`. Ein Prozentwert im
  Dashboard stammt immer aus einem Import — nie vom eigenen Armband. Die Rohdaten sind da, die
  Umrechnung fehlt.
- **Atemfrequenz kommt nicht vom Atemsensor.** `respSample` wird noch befüllt, aber WHOOP 5
  liefert dort nichts Brauchbares. Der angezeigte Wert wird aus der Pulsvariabilität über die
  respiratorische Sinusarrhythmie geschätzt.
- **Schritte und Kalorien sind Näherungen.** Die Schrittsemantik des Armband-Zählers ist nicht
  gegen die Hersteller-App verifiziert; die Kalorien sind eine Formel auf der Herzfrequenz.
- **Die Hauttemperatur-Baseline ist noch nicht vollständig kausal.** Anders als HRV und Ruhepuls
  wird das nächtliche Mittel nirgends als eigene Spalte gespeichert — die Tageszeile hält nur die
  *Abweichung*. Deshalb bildet sich diese eine Baseline aus dem, was der jeweilige Durchlauf
  gerade gelesen hat. Der Term wiegt 0,05 und fällt ganz heraus, solange seine Baseline nicht
  brauchbar ist; die Behebung braucht eine Schema-Spalte.
