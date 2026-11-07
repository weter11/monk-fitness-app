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

## Regenerating

```bash
mkdir -p /tmp/sources/usda /tmp/sources/ciqual
curl -O <the three URLs above>            # into /tmp/sources
python3 data/nutrition/extract.py /tmp/sources data/nutrition
```
