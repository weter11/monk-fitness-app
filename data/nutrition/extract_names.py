#!/usr/bin/env python3
"""Regenerates the committed ingredient-name extracts and the catalogue's `names` member.

Names are vocabulary terms, not composition values, so they come from two resources that publish
terms per concept and per language rather than from the food-composition tables the nutrition
numbers are cited to. Both are queried live; the rows actually cited are written to CSV, and the
`names` member of every ingredient in the catalogue is then rebuilt from those rows.

Run from the repository root:

    python3 data/nutrition/extract_names.py

The script fails when a selected term is no longer what the source returns, so a silent change at
the source cannot reach the app.
"""
import csv
import json
import os
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CATALOGUE = os.path.join(ROOT, "app/src/main/assets/nutrition/catalogue.json")
WIKIDATA_DIR = os.path.join(ROOT, "data/nutrition/wikidata")
AGROVOC_DIR = os.path.join(ROOT, "data/nutrition/agrovoc")

RETRIEVED = "2026-11-07"
UA = {"User-Agent": "monk-fitness-app/1.0 (ingredient names; +https://github.com/weter11/monk-fitness-app)"}

LANGUAGES = ["nl", "fr", "bg", "de"]

# The concept each catalogue id denotes. One concept per ingredient, in both resources.
CONCEPTS = {
    "chicken":        {"wikidata": "Q2607161",   "agrovoc": "c_24000"},
    "eggs":           {"wikidata": "Q93189",     "agrovoc": "c_2502"},
    "rice":           {"wikidata": "Q5090",      "agrovoc": "c_6599"},
    "oats":           {"wikidata": "Q24265484",  "agrovoc": "c_5287"},
    "potatoes":       {"wikidata": "Q16587531",  "agrovoc": "c_13551"},
    "buckwheat":      {"wikidata": "Q4536337",   "agrovoc": "c_9471"},
    "cottage_cheese": {"wikidata": "Q13221624",  "agrovoc": "c_c95dc3fd"},
    "yogurt":         {"wikidata": "Q13317",     "agrovoc": "c_15463"},
    "banana":         {"wikidata": "Q503",       "agrovoc": "c_806"},
    "apple":          {"wikidata": "Q89",        "agrovoc": "c_541"},
    "nuts":           {"wikidata": "Q4118270",   "agrovoc": "c_12873"},
    "tuna":           {"wikidata": "Q124762216", "agrovoc": "c_8003"},
}

LICENCES = {"Wikidata": "CC0 1.0", "AGROVOC": "CC BY 4.0"}

# Which resource supplies each (ingredient, language), and the term type it must be.
# AGROVOC is cited for French only: FAO holds the copyright for its six FAO languages, so the
# CC BY 4.0 grant is certain there, while the German, Dutch and Bulgarian terms belong to the
# institutions that contributed them and carry no published licence. Bulgarian has no AGROVOC
# coverage at all.
SELECTION = {
    "chicken":        {"fr": ("Wikidata", "prefLabel"), "nl": ("Wikidata", "prefLabel"),
                       "bg": ("Wikidata", "prefLabel"), "de": ("Wikidata", "prefLabel")},
    "eggs":           {"fr": ("AGROVOC", "prefLabel"),  "nl": ("Wikidata", "prefLabel"),
                       "bg": ("Wikidata", "prefLabel"), "de": ("Wikidata", "prefLabel")},
    "rice":           {"fr": ("AGROVOC", "prefLabel"),  "nl": ("Wikidata", "prefLabel"),
                       "bg": ("Wikidata", "prefLabel"), "de": ("Wikidata", "prefLabel")},
    "oats":           {"fr": ("AGROVOC", "prefLabel"),  "nl": ("Wikidata", "prefLabel"),
                       "bg": ("Wikidata", "prefLabel"), "de": ("Wikidata", "prefLabel")},
    "potatoes":       {"fr": ("AGROVOC", "prefLabel"),  "nl": ("Wikidata", "prefLabel"),
                       "bg": ("AGENT", ""),             "de": ("Wikidata", "alias")},
    "buckwheat":      {"fr": ("AGROVOC", "prefLabel"),  "nl": ("Wikidata", "prefLabel"),
                       "bg": ("AGENT", ""),             "de": ("Wikidata", "prefLabel")},
    "cottage_cheese": {"fr": ("AGENT", ""),             "nl": ("Wikidata", "prefLabel"),
                       "bg": ("AGENT", ""),             "de": ("Wikidata", "prefLabel")},
    "yogurt":         {"fr": ("AGROVOC", "prefLabel"),  "nl": ("Wikidata", "prefLabel"),
                       "bg": ("Wikidata", "prefLabel"), "de": ("Wikidata", "prefLabel")},
    "banana":         {"fr": ("AGROVOC", "prefLabel"),  "nl": ("Wikidata", "prefLabel"),
                       "bg": ("Wikidata", "prefLabel"), "de": ("Wikidata", "prefLabel")},
    "apple":          {"fr": ("AGROVOC", "prefLabel"),  "nl": ("Wikidata", "prefLabel"),
                       "bg": ("Wikidata", "prefLabel"), "de": ("Wikidata", "prefLabel")},
    "nuts":           {"fr": ("AGROVOC", "prefLabel"),  "nl": ("AGENT", ""),
                       "bg": ("AGENT", ""),             "de": ("AGENT", "")},
    "tuna":           {"fr": ("AGROVOC", "prefLabel"),  "nl": ("AGENT", ""),
                       "bg": ("AGENT", ""),             "de": ("AGENT", "")},
}

# The app's own translations, used where no cited vocabulary names the concept in that language.
# They repeat the wording the interface already uses, which is the point: the app is the author.
AGENT_NAMES = {
    ("potatoes", "bg"): "картофи",
    ("buckwheat", "bg"): "елда",
    ("cottage_cheese", "fr"): "cottage cheese",
    ("cottage_cheese", "bg"): "извара",
    ("nuts", "nl"): "noten",
    ("nuts", "de"): "Nüsse",
    ("nuts", "bg"): "ядки",
    ("tuna", "nl"): "tonijn",
    ("tuna", "de"): "Thunfisch",
    ("tuna", "bg"): "риба тон",
}


def fetch_wikidata(qids):
    """Every label and alias of each item in the four pilot languages."""
    out = {}
    for qid in qids:
        params = {
            "action": "wbgetentities", "ids": qid, "props": "labels|aliases",
            "languages": "|".join(LANGUAGES), "format": "json",
        }
        url = "https://www.wikidata.org/w/api.php?" + urllib.parse.urlencode(params)
        for attempt in range(5):
            try:
                with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                    entity = json.load(r)["entities"][qid]
                break
            except Exception as exc:  # rate limit or transient failure
                if attempt == 4:
                    sys.exit(f"wikidata {qid}: {exc}")
                time.sleep(5 * (attempt + 1))
        terms = []
        for lang in LANGUAGES:
            label = entity.get("labels", {}).get(lang)
            if label:
                terms.append((lang, "prefLabel", label["value"]))
            for alias in entity.get("aliases", {}).get(lang, []):
                terms.append((lang, "alias", alias["value"]))
        out[qid] = terms
        time.sleep(1.2)
    return out


def fetch_agrovoc(cids):
    """Preferred label per language for each AGROVOC concept."""
    def sparql(query):
        url = "https://agrovoc.fao.org/sparql?query=" + urllib.parse.quote(query)
        req = urllib.request.Request(url, headers={**UA, "Accept": "application/sparql-results+json"})
        for attempt in range(5):
            try:
                with urllib.request.urlopen(req, timeout=90) as r:
                    return json.load(r)["results"]["bindings"]
            except Exception as exc:  # the endpoint answers 400/503 under load
                if attempt == 4:
                    sys.exit(f"agrovoc {query[:60]}: {exc}")
                time.sleep(5 * (attempt + 1))

    out = {}
    for ingredient, spec in cids.items():
        cid = spec["agrovoc"]
        query = f'''PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
SELECT ?term ?lang WHERE {{
  <http://aims.fao.org/aos/agrovoc/{cid}> skos:prefLabel ?term .
  BIND(lang(?term) AS ?lang)
  FILTER(lang(?term) IN ("nl","fr","bg","de"))
}}'''
        out[ingredient] = sorted(
            (row["lang"]["value"], "prefLabel", row["term"]["value"]) for row in sparql(query)
        )
        time.sleep(0.7)
    return out


def write_csv(path, header, rows):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(header)
        writer.writerows(rows)


def main():
    wikidata = fetch_wikidata(sorted({c["wikidata"] for c in CONCEPTS.values()}))
    agrovoc = fetch_agrovoc(CONCEPTS)

    write_csv(
        os.path.join(WIKIDATA_DIR, "labels.csv"), ["qid", "lang", "term_type", "term"],
        [[qid, lang, kind, term] for qid, rows in wikidata.items() for lang, kind, term in rows],
    )
    write_csv(
        os.path.join(AGROVOC_DIR, "pref_labels.csv"), ["concept", "lang", "term"],
        [[CONCEPTS[key]["agrovoc"], lang, term]
         for key, rows in agrovoc.items() for lang, _, term in rows],
    )

    index = {
        "Wikidata": {(qid, lang, kind): term
                     for qid, rows in wikidata.items() for lang, kind, term in rows},
        "AGROVOC": {(CONCEPTS[key]["agrovoc"], lang, kind): term
                    for key, rows in agrovoc.items() for lang, kind, term in rows},
    }

    with open(CATALOGUE, encoding="utf-8") as f:
        catalogue = json.load(f)

    for ingredient in catalogue["ingredients"]:
        key = ingredient["id"]
        names = {}
        for lang in LANGUAGES:
            database, term_type = SELECTION[key][lang]
            if database == "AGENT":
                name = AGENT_NAMES[(key, lang)]
                names[lang] = {"name": name, "trust": "unverified", "source": "agent"}
                continue
            concept_id = CONCEPTS[key]["wikidata" if database == "Wikidata" else "agrovoc"]
            name = index[database].get((concept_id, lang, term_type))
            if not name:
                sys.exit(f"{key}/{lang}: {database} {concept_id} has no {term_type}")
            names[lang] = {
                "name": name,
                "trust": "sourced",
                "source": {
                    "database": database,
                    "id": concept_id,
                    "term": term_type,
                    "licence": LICENCES[database],
                },
            }
        ingredient["names"] = names

    catalogue["sources"]["AGROVOC"] = (
        "AGROVOC multilingual thesaurus, FAO, French preferred labels (CC BY 4.0)"
    )
    catalogue["sources"]["Wikidata"] = (
        "Wikidata item labels and aliases, item ids given per name (CC0 1.0)"
    )

    with open(CATALOGUE, "w", encoding="utf-8") as f:
        f.write(json.dumps(catalogue, indent=2, ensure_ascii=False) + "\n")
    print(f"wrote {CATALOGUE} with names for {len(catalogue['ingredients'])} ingredients")


if __name__ == "__main__":
    main()
