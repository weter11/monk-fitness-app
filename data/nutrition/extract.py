#!/usr/bin/env python3
"""Regenerates the committed source extracts from the downloaded public exports.

The archives are not committed; their sha256 digests are in SOURCES.md. Run from the
repository root after downloading them into the paths below.
"""
import csv
import os
import re
import sys
import zipfile

USDA_SR = "FoodData_Central_sr_legacy_food_csv_2018-04"
USDA_FOUNDATION = "FoodData_Central_foundation_food_csv_2024-10-31"
CIQUAL_XML = ["alim_2020_07_07.xml", "compo_2020_07_07.xml"]

FDC_IDS = {
    171534: "chicken", 173424: "eggs", 168878: "rice", 173904: "oats",
    170440: "potatoes", 170686: "buckwheat", 172182: "cottage_cheese",
    171284: "yogurt", 173944: "banana", 171688: "apple", 170585: "nuts",
    171986: "tuna",
    170393: "carrots", 170457: "tomatoes", 169291: "courgette", 169986: "cauliflower",
    172421: "lentils", 173757: "chickpeas", 175167: "salmon", 171955: "cod",
    169736: "pasta", 172688: "bread", 172456: "soy_milk", 172475: "tofu",
    173863: "ham", 171267: "milk",
}
NUTRIENTS = {"1008": "kcal", "1003": "protein_g", "1004": "fat_g", "1005": "carb_g"}
PORTIONS = {173424: "3", 173944: "5", 171688: "4"}
CIQUAL_CODES = {
    "36018": "chicken", "22010": "eggs", "9104": "rice", "9310": "oats",
    "4003": "potatoes", "9380": "buckwheat", "19646": "cottage_cheese",
    "19600": "yogurt", "13005": "banana", "13620": "apple", "15054": "nuts",
    "26039": "tuna",
    "20009": "carrots", "20047": "tomatoes", "20020": "courgette", "20016": "cauliflower",
    "20587": "lentils", "20507": "chickpeas", "26036": "salmon", "26043": "cod",
    "9810": "pasta", "7110": "bread", "18901": "soy_milk", "20904": "tofu",
    "28913": "ham", "19041": "milk",
}
CIQUAL_CONSTITUENTS = {"328": "kcal", "25000": "protein_g", "40000": "fat_g", "31000": "carb_g"}


def unzip_csvs(archive, dest):
    with zipfile.ZipFile(archive) as zf:
        for name in zf.namelist():
            if name.endswith(".csv"):
                target = os.path.join(dest, name)
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with zf.open(name) as src, open(target, "wb") as out:
                    out.write(src.read())


def extract_usda(workdir, outdir):
    foods = {int(r["fdc_id"]): r for r in csv.DictReader(open(os.path.join(workdir, USDA_SR, "food.csv"), encoding="utf-8")) if int(r["fdc_id"]) in FDC_IDS}
    with open(os.path.join(outdir, "foods.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["fdc_id", "description", "data_type"])
        for fdc_id in sorted(foods):
            w.writerow([fdc_id, foods[fdc_id]["description"], foods[fdc_id]["data_type"]])
    with open(os.path.join(outdir, "food_nutrients.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["fdc_id", "nutrient_id", "amount"])
        for r in csv.DictReader(open(os.path.join(workdir, USDA_SR, "food_nutrient.csv"), encoding="utf-8")):
            if int(r["fdc_id"]) in FDC_IDS and r["nutrient_id"] in NUTRIENTS:
                w.writerow([r["fdc_id"], r["nutrient_id"], r["amount"]])
    with open(os.path.join(outdir, "food_portions.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["id", "fdc_id", "seq_num", "amount", "modifier", "gram_weight"])
        for r in csv.DictReader(open(os.path.join(workdir, USDA_SR, "food_portion.csv"), encoding="utf-8")):
            fdc_id = int(r["fdc_id"])
            if PORTIONS.get(fdc_id) == r["seq_num"]:
                w.writerow([r["id"], fdc_id, r["seq_num"], r["amount"], r["modifier"], r["gram_weight"]])


def ciqual_blocks(text, tag):
    return re.findall(r"<%s>(.*?)</%s>" % (tag, tag), text, re.S)


def ciqual_field(block, tag):
    found = re.search(r"<%s>(.*?)</%s>" % (tag, tag), block, re.S)
    return found.group(1).strip() if found else ""


def extract_ciqual(workdir, outdir):
    alim = open(os.path.join(workdir, CIQUAL_XML[0]), "rb").read().decode("cp1252")
    names = {}
    for block in ciqual_blocks(alim, "ALIM"):
        code = ciqual_field(block, "alim_code")
        if code in CIQUAL_CODES:
            names[code] = (ciqual_field(block, "alim_nom_eng"), ciqual_field(block, "alim_nom_fr"))
    with open(os.path.join(outdir, "alim.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["alim_code", "alim_nom_eng", "alim_nom_fr"])
        for code in sorted(names):
            w.writerow([code, names[code][0], names[code][1]])
    compo = open(os.path.join(workdir, CIQUAL_XML[1]), "rb").read().decode("cp1252")
    with open(os.path.join(outdir, "compo.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["alim_code", "const_code", "teneur"])
        for block in ciqual_blocks(compo, "COMPO"):
            code = ciqual_field(block, "alim_code")
            constituent = ciqual_field(block, "const_code")
            if code in CIQUAL_CODES and constituent in CIQUAL_CONSTITUENTS:
                w.writerow([code, constituent, ciqual_field(block, "teneur")])


if __name__ == "__main__":
    source_dir, data_dir = sys.argv[1], sys.argv[2]
    usda_work = os.path.join(source_dir, "usda")
    ciqual_work = os.path.join(source_dir, "ciqual")
    os.makedirs(usda_work, exist_ok=True)
    os.makedirs(ciqual_work, exist_ok=True)
    if not os.path.isdir(os.path.join(usda_work, USDA_SR)):
        unzip_csvs(os.path.join(source_dir, USDA_SR + ".zip"), usda_work)
    if not os.path.isdir(os.path.join(usda_work, USDA_FOUNDATION)):
        unzip_csvs(os.path.join(source_dir, USDA_FOUNDATION + ".zip"), usda_work)
    if not os.path.isfile(os.path.join(ciqual_work, CIQUAL_XML[0])):
        with zipfile.ZipFile(os.path.join(source_dir, "ciqual_2020_xml.zip")) as zf:
            zf.extractall(ciqual_work)
    extract_usda(usda_work, os.path.join(data_dir, "usda_fdc"))
    extract_ciqual(ciqual_work, os.path.join(data_dir, "ciqual_2020"))
