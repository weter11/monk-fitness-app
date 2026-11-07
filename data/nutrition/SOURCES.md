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
| fr | AGROVOC, plus Wikidata for chicken breast | 11 of 12 |
| nl | Wikidata | 10 of 12 |
| de | Wikidata | 10 of 12 |
| bg | Wikidata | 7 of 12 |

The rest are the app's own translations, marked `unverified` with `source: "agent"`.

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
