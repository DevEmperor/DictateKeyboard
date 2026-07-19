import re, os, glob

import pathlib
ROOT = str(pathlib.Path(__file__).resolve().parent.parent)
SETTINGS = f"{ROOT}/app/src/main/kotlin/dev/patrickgold/florisboard/app/settings"

# screen composable -> Routes.Settings.X kotlin expression
SCREEN_ROUTE = {
    "DictateScreen": "Routes.Settings.Dictate",
    "DictateLanguagesScreen": "Routes.Settings.DictateLanguages",
    "DictateProvidersScreen": "Routes.Settings.DictateProviders",
    "DictateMappingsScreen": "Routes.Settings.DictateMappings",
    "DictateProxyScreen": "Routes.Settings.DictateProxy",
    "DictateWearScreen": "Routes.Settings.DictateWear",
    "DictateRewordingScreen": "Routes.Settings.DictateRewording",
    "DictateFormattingScreen": "Routes.Settings.DictateFormatting",
    "DictateRecordingScreen": "Routes.Settings.DictateRecording",
    "DictateOutputScreen": "Routes.Settings.DictateOutput",
    "DictateLayoutScreen": "Routes.Settings.DictateLayout",
    "DictateStatsScreen": "Routes.Settings.DictateStats",
    "DictateHistoryScreen": "Routes.Settings.DictateHistory",
    "DictateFloatingButtonScreen": "Routes.Settings.DictateFloatingButton",
    "DictatePromptLibraryScreen": "Routes.Settings.DictatePromptLibrary",
    "DictatePromptsScreen": "Routes.Settings.DictatePrompts()",
    "LocalizationScreen": "Routes.Settings.Localization",
    "ThemeScreen": "Routes.Settings.Theme",
    "KeyboardScreen": "Routes.Settings.Keyboard",
    "InputFeedbackScreen": "Routes.Settings.InputFeedback",
    "SmartbarScreen": "Routes.Settings.Smartbar",
    "TypingScreen": "Routes.Settings.Typing",
    "DictionaryScreen": "Routes.Settings.Dictionary",
    "GesturesScreen": "Routes.Settings.Gestures",
    "ClipboardScreen": "Routes.Settings.Clipboard",
    "MediaScreen": "Routes.Settings.Media",
    "OtherScreen": "Routes.Settings.Other",
    "PhysicalKeyboardScreen": "Routes.Settings.PhysicalKeyboard",
    "BackupScreen": "Routes.Settings.Backup",
    "RestoreScreen": "Routes.Settings.Restore",
    "AboutScreen": "Routes.Settings.About",
}

# breadcrumb parent (top-level section) per screen -> R.string of the parent screen title
DICTATE = "R.string.dictate__title"
PARENT = {}
for s in ["DictateLanguagesScreen","DictateProvidersScreen","DictateMappingsScreen","DictateProxyScreen",
          "DictateWearScreen","DictateRewordingScreen","DictateFormattingScreen","DictateRecordingScreen",
          "DictateOutputScreen","DictateLayoutScreen","DictateStatsScreen","DictateHistoryScreen","DictateFloatingButtonScreen",
          "DictatePromptLibraryScreen","DictatePromptsScreen"]:
    PARENT[s] = DICTATE
for s in ["PhysicalKeyboardScreen","BackupScreen","RestoreScreen"]:
    PARENT[s] = "R.string.settings__other__title"
PARENT["InputFeedbackScreen"] = "R.string.settings__keyboard__title"
for s in ["ProjectLicenseScreen","ThirdPartyLicensesScreen","DataAttributionsScreen"]:
    PARENT[s] = "R.string.about__title"

ANCHORABLE = {"Preference","SwitchPreference","ListPreference","DialogSliderPreference","ColorPickerPreference"}
CUSTOM_ROWS = {"PromptSelectionPreference","TextInputPreference","HistoryToggleRow","OptionRow"}
ROW_OPENERS = ANCHORABLE | CUSTOM_ROWS

title_re = re.compile(r'title = stringRes\(R\.string\.([A-Za-z0-9_]+)\)')
title_alone_re = re.compile(r'^(\s*)title = stringRes\(R\.string\.[A-Za-z0-9_]+\),\s*$')
opener_re = re.compile(r'^\s*([A-Za-z][A-Za-z0-9_]*)\(')
func_re = re.compile(r'^fun ([A-Za-z0-9_]+)\(\) = FlorisScreen \{')

def nearest_opener(lines, i):
    # scan backward for the nearest composable-call opener line
    depth_paren = 0
    for j in range(i-1, max(-1, i-14), -1):
        m = opener_re.match(lines[j])
        if m:
            return m.group(1), j
    return None, None

entries = []          # (titleRes, sectionRes, routeExpr, parentRes|None, anchor|None)
insertions = {}       # file -> list of (lineIndex, indent, key)
screens_seen = []

for path in sorted(glob.glob(f"{SETTINGS}/**/*.kt", recursive=True)):
    if "/search/" in path: continue
    src = open(path).read()
    lines = src.split("\n")
    # find function blocks
    funcs = [(m := func_re.match(l)) and (i, m.group(1)) for i,l in enumerate(lines)]
    funcs = [f for f in funcs if f]
    for idx,(start,fname) in enumerate(funcs):
        if fname not in SCREEN_ROUTE: continue
        end = funcs[idx+1][0] if idx+1 < len(funcs) else len(lines)
        route = SCREEN_ROUTE[fname]
        parent = PARENT.get(fname)
        block = lines[start:end]
        # screen title = first title occurrence in block
        screen_title = None
        for l in block:
            m = title_re.search(l)
            if m: screen_title = m.group(1); break
        if not screen_title: continue
        screens_seen.append((fname, screen_title, route, parent))
        seen_here = set()
        # rows
        for bi,l in enumerate(block):
            i = start + bi
            m = title_re.search(l)
            if not m: continue
            key = m.group(1)
            if key == screen_title and bi == block.index(next(x for x in block if screen_title in x)):
                continue  # the screen title line itself
            op, opline = nearest_opener(lines, i)
            if op not in ROW_OPENERS: continue
            if key in seen_here: continue
            seen_here.add(key)
            anchor = None
            am = title_alone_re.match(lines[i])
            if op in ANCHORABLE and am:
                call = range(opline, i)
                has_anchor = any("settingsSearchAnchor(" in lines[k] for k in call)
                has_other_mod = any("modifier =" in lines[k] for k in call)
                if has_anchor:
                    anchor = key                      # already wired (idempotent re-run)
                elif not has_other_mod:
                    anchor = key
                    insertions.setdefault(path, []).append((i, am.group(1), key))
                # else: a non-anchor modifier is present; can't safely wire → leave unanchored
            entries.append((key, screen_title, route, parent, anchor))

# also add a screen-level entry per screen (so the screen name is findable)
screen_level = []
for fname, stitle, route, parent in screens_seen:
    screen_level.append((stitle, stitle, route, parent, None))

# de-dup entries by (titleRes, route)
def dedup(lst):
    out=[]; seen=set()
    for e in lst:
        k=(e[0], e[2])
        if k in seen: continue
        seen.add(k); out.append(e)
    return out

# apply anchor insertions (bottom-up) + imports
for path, ins in insertions.items():
    lines = open(path).read().split("\n")
    for i, indent, key in sorted(ins, key=lambda x:-x[0]):
        lines.insert(i, f'{indent}modifier = Modifier.settingsSearchAnchor("{key}"),')
    src = "\n".join(lines)
    if "import androidx.compose.ui.Modifier\n" not in src and "\nimport androidx.compose.ui.Modifier" not in src:
        # insert after first androidx.compose.ui. import, else before first `import dev.patrickgold.florisboard.R`
        m = re.search(r'^import androidx\.compose\.ui\..*$', src, re.M)
        if m:
            src = src[:m.start()] + "import androidx.compose.ui.Modifier\n" + src[m.start():]
        else:
            src = src.replace("import dev.patrickgold.florisboard.R",
                              "import androidx.compose.ui.Modifier\nimport dev.patrickgold.florisboard.R", 1)
    if "app.settings.search.settingsSearchAnchor" not in src:
        src = src.replace("import dev.patrickgold.florisboard.R",
                          "import dev.patrickgold.florisboard.R\nimport dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor", 1)
    open(path,"w").write(src)

all_entries = dedup(screen_level) + dedup(entries)
# final global dedup preserving screen-level first
final = dedup(all_entries)

# emit index
def route_short(r):
    return r.replace("Routes.Settings.","").replace("()","")

lines_out = []
lines_out.append("/*")
lines_out.append(" * Copyright (C) 2026 DevEmperor (Dictate)")
lines_out.append(" *")
lines_out.append(' * Licensed under the Apache License, Version 2.0 (the "License");')
lines_out.append(" * you may not use this file except in compliance with the License.")
lines_out.append(" * You may obtain a copy of the License at")
lines_out.append(" *")
lines_out.append(" *     http://www.apache.org/licenses/LICENSE-2.0")
lines_out.append(" */")
lines_out.append("")
lines_out.append("package dev.patrickgold.florisboard.app.settings.search")
lines_out.append("")
lines_out.append("import androidx.annotation.StringRes")
lines_out.append("import dev.patrickgold.florisboard.R")
lines_out.append("import dev.patrickgold.florisboard.app.Routes")
lines_out.append("")
lines_out.append("/**")
lines_out.append(" * One searchable settings entry (issue #187). GENERATED from the settings screens by")
lines_out.append(" * scripts/gen_settings_search_index — do not curate by hand; rerun the generator when settings")
lines_out.append(" * change so the search stays complete. Each entry maps a localized [titleRes] to the [route]")
lines_out.append(" * that shows it, with [sectionRes] (the screen) and optional [parentRes] forming the breadcrumb.")
lines_out.append(" * When [anchor] is set the destination row is tagged with Modifier.settingsSearchAnchor and the")
lines_out.append(" * result scrolls to + highlights it; otherwise it lands on the screen.")
lines_out.append(" */")
lines_out.append("data class SettingsSearchEntry(")
lines_out.append("    @StringRes val titleRes: Int,")
lines_out.append("    @StringRes val sectionRes: Int,")
lines_out.append("    val route: Any,")
lines_out.append("    @StringRes val parentRes: Int? = null,")
lines_out.append("    val anchor: String? = null,")
lines_out.append(")")
lines_out.append("")
lines_out.append("object SettingsSearchIndex {")
lines_out.append("    val entries: List<SettingsSearchEntry> = listOf(")
for (key, sect, route, parent, anchor) in final:
    p = f", parentRes = R.string.{parent.replace('R.string.','')}" if parent else ""
    a = f', anchor = "{anchor}"' if anchor else ""
    lines_out.append(f"        SettingsSearchEntry(R.string.{key}, R.string.{sect}, {route}{p}{a}),")
lines_out.append("    )")
lines_out.append("}")

open(f"{SETTINGS}/search/SettingsSearchIndex.kt","w").write("\n".join(lines_out)+"\n")

anchored = sum(1 for e in final if e[4])
print(f"screens indexed: {len(screens_seen)}")
print(f"total entries: {len(final)}  (anchored: {anchored})")
print(f"files wired with anchors: {len(insertions)}")
