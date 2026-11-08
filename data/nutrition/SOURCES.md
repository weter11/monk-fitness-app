# Nutrition catalogue sources

Every number in `app/src/main/assets/nutrition/catalogue.json` comes from a row of the exports below.
The archives themselves are not committed; their digests identify the exact files the rows were taken
from. `extract.py` regenerates the extracts from them.

| File committed | Archive | URL | Retrieved | sha256 |
| --- | --- | --- | --- | --- |
| `usda_fdc/foods.csv`, `usda_fdc/food_nutrients.csv`, `usda_fdc/food_portions.csv` | `FoodData_Central_sr_legacy_food_csv_2018-04.zip` | https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip | 2026-11-07 | `b80817294b8850530aaedf2e515c02593b1824f763a0ff356e5c2081643e6fd0` |
| (Foundation Foods rows, cross-checked only) | `FoodData_Central_foundation_food_csv_2024-10-31.zip` | https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_foundation_food_csv_2024-10-31.zip | 2026-11-07 | `8587f941ec252bd9aaf7b85a18a42980fb540f509b34300ed8dd4d10ca9edf44` |
| `ciqual_2020/alim.csv`, `ciqual_2020/compo.csv` | `XML_2020_07_07.zip` (ANSES-CIQUAL 2020, English XML) | https://ciqual.anses.fr/cms/sites/default/files/inline-files/XML_2020_07_07.zip | 2026-11-07 | `cab13941ca693b7007c2f0fed2288fd5b94073fe6d598c9773297cf37aa59ca8` |

The CIQUAL origin returned HTTP 503 (site maintenance) on the retrieval date. The archive was fetched
from the Internet Archive's snapshot of that exact URL
(`https://web.archive.org/web/20210715055646id_/https://ciqual.anses.fr/cms/sites/default/files/inline-files/XML_2020_07_07.zip`),
and its 3,554,394 bytes match the `content-length` data.gouv.fr recorded for the origin URL. The digest
above is of the bytes actually analysed.

## Licences

- USDA FoodData Central (Foundation Foods, SR Legacy): U.S. Government work, public domain (CC0).
- ANSES-CIQUAL 2020: Licence Ouverte / Open Licence 2.0 (Etalab). Reuse including commercial is
  permitted with attribution of the source; the app names ANSES Ciqual 2020 on the Nutrition screen.
- Open Food Facts was not used: its ODbL licence does not fit shipped values.

## Ingredient names

A name is a *word* for a food, not a measurement of it, so it is not taken from the composition tables
above. Those name the analysed preparation ("Fromage blanc, nature, 3% MG", "Kartoffeln, geschält,
gegart"), and the row a number came from may not even be the food the ingredient stands for — CIQUAL's
fresh cream cheese stands in for cottage cheese, grilled cashew for mixed nuts. Names are cited per
concept to a vocabulary instead.

| File committed | Source | URL | Retrieved |
| --- | --- | --- | --- |
| `wikidata/labels.csv` | Wikidata item labels and aliases, `action=wbgetentities` | https://www.wikidata.org/w/api.php | 2026-11-07 |
| `agrovoc/pref_labels.csv` | AGROVOC multilingual thesaurus, SPARQL endpoint | https://agrovoc.fao.org/sparql | 2026-11-07 |

`extract_names.py` queries both, writes the rows quoted in the catalogue, and rebuilds every
ingredient's `names` member from them. It fails rather than writing a term the source no longer
returns.

### Licences, per language

- **Wikidata**: CC0 1.0. No attribution required; the app names it anyway, and every name carries the
  item id it was read from.
- **AGROVOC**: CC BY 4.0 for the six FAO languages (en, fr, es, ar, ru, zh), whose copyright is FAO's.
  Content in *other* languages — German, Dutch, Bulgarian — rests with the institutions that
  contributed it, and FAO publishes no licence for those terms. Only French is therefore cited, and
  the app credits FAO and the licence.

### Coverage actually found, per language

| Language | Cited from | Concepts with a usable term |
| --- | --- | --- |
| fr | AGROVOC, plus Wikidata for chicken breast | 25 of 26 |
| nl | Wikidata | 24 of 26 |
| de | Wikidata | 23 of 26 |
| bg | Wikidata | 19 of 26 |

The rest are the app's own translations, marked `unverified` with `source: "agent"` — Bulgarian for
lentils and wholemeal bread, German for cod, and the five entries this pilot already carried.

Wikidata returns several aliases per language, and the reader indexes one term per (item, language,
type). The extract therefore keeps only the alias a name actually cites: `tomaten` for the tomato
(Wikidata's own label names the plant), `Lachs` for the salmon (its label is the plural `Lachse`) and
`kabeljauw` for the cod (its label names the species).

### Cases where the second table publishes no energy for the row

CIQUAL leaves `328` (energy, kcal) empty for many raw vegetables and dried pulses — broccoli, spinach
and dried lentils among them. A second source is only usable here if it carries the number, so the row
chosen for carrots, tomatoes, courgette, cauliflower, lentils, chickpeas and pasta is the one that
does, and the `basis` field says which preparation that row is. Chickpeas are the one genuine overrun
(10.4%): CIQUAL's boiled chickpeas hold more water than USDA's, and the note on the entry says so.

### Form

Names are stored the way a dictionary writes them — lowercase for Dutch, French and Bulgarian;
German nouns capitalised — so a vocabulary label that arrives capitalised is lowercased on the way in
(`Яйце` → `яйца`, not because the source is wrong but because a shelf label is not an entry).

Foods bought by the piece are stored in the plural (`eieren`, `pommes de terre`, `яйца`, `Eier`) and
carry `inflectedFrom`, the singular the cited label spells. A reader can therefore still check the
plural against the row: the validator compares `inflectedFrom` to the cited term, folding case and the
œ ligature. Foods bought by weight (`chicken`, `rice`, `oats`, `buckwheat`, `cottage_cheese`,
`yogurt`, `tuna`, and every vegetable, pulse, fish, pasta, bread, drink and dairy item added later)
keep one form and carry no `inflectedFrom`. An `unverified` plural that the app wrote itself
(`noten`, `картофи`, `ядки`, `Nüsse`) has no label to point at and so carries none.

French eggs are the one place the app deliberately departs from the source's spelling: AGROVOC types
`oeuf`, French writes `œuf`, and the row shows `Œufs`. The `note` on that entry says so.

### What the shops say (not a source — evidence for the word chosen)

No open vocabulary names cottage cheese in Dutch, French or Bulgarian, so the app chose the word and
recorded why on the entry's `note`:

| Market | Word | Evidence |
| --- | --- | --- |
| NL / BE (nl) | `hüttenkäse` (Wikidata label) | Albert Heijn sells [Danone Hüttenkäse](https://www.ah.nl/producten/product/wi1932/danone-h%C3%BCttenk%C3%A4se-original) and its own [Cottage cheese](https://www.ah.nl/producten/product/wi400467/ah-cottage-cheese) in a cabinet literally named “Cottage cheese”, describing the Danone tub as “huttenkase, ook wel cottage cheese genoemd”; its Belgian site sells [the same tub](https://www.ah.be/producten/product/wi67552/danone-h%C3%BCttenk%C3%A4se-original). Both words are current, so the vocabulary's word is kept. |
| BE / FR (fr) | `cottage cheese` (agent) | Carrefour Belgium lists [“Cottage Cheese Fromage Frais”](https://www.carrefour.be/fr/cottage-cheese-fromage-frais-riche-en-proteines-200-g/00959702.html); Delhaize's Belgian product is [“Cottage Cheese”](https://world.openfoodfacts.org/product/5400117596901/cottage-cheese-delhaize). French has no settled name for the product and `fromage blanc` is a different one. |
| BG | `извара` (agent) | The closest product on Bulgarian shelves; cottage cheese is not sold in mainstream Bulgarian retail, so the note on the entry says exactly that. |

### Sources checked and not used

| Source | Verdict |
| --- | --- |
| NEVO (RIVM, Dutch) | Rejected: RIVM/Kingdom of the Netherlands copyright, reuse permitted only unchanged and with the version cited — not an open licence. Dutch names only. |
| Fineli (THL, Finnish) | Rejected on language coverage: CC BY 4.0, but food names are published in Finnish, Swedish and English only — none of the four pilot languages. |
| BLS 4.0 (Max Rubner-Institut, German) | Rejected on fit, not licence: free under CC BY 4.0 since version 4.0 (December 2025), but its names describe analysed preparations in a composition table, which is the stand-in problem this pilot exists to avoid. |
| CIQUAL 2020 (ANSES, French) | Rejected for names: it is the stand-in table itself. Its French rows are preparations, and one of them ("Fromage blanc") is exactly what cottage cheese must not be called. Still cited for numbers. |
| AGROVOC, German / Dutch / Bulgarian terms | Rejected: the thesaurus holds them (39,196 German, 23,436 Dutch), but their copyright sits with contributing institutions and no licence is published. Bulgarian has no AGROVOC coverage at all (34 preferred terms). |

## Regenerating

```bash
mkdir -p /tmp/sources/usda /tmp/sources/ciqual
curl -O <the three URLs above>            # into /tmp/sources
python3 data/nutrition/extract.py /tmp/sources data/nutrition
python3 data/nutrition/extract_names.py   # queries Wikidata and AGROVOC directly
```
