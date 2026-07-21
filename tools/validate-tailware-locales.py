# Copyright (c) Tailscale Inc & AUTHORS
# SPDX-License-Identifier: BSD-3-Clause

"""Validate and export Tailware's complete Wear OS localization catalog."""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android/src/main/res"
BASE_CATALOG = RES / "values/wear_strings.xml"
LOCALES_CONFIG = RES / "xml/locales_config.xml"
ENGLISH_EXPORT = ROOT / "docs/localization/tailware-english-catalog.json"
APP_RESTRICTIONS = RES / "xml/app_restrictions.xml"
MANAGED_CATALOG_FILENAME = "managed_configuration.xml"
ANDROID_ATTRIBUTE = "{http://schemas.android.com/apk/res/android}"

LOCALE_DIRECTORIES = {
    "en": "values",
    "ar": "values-ar",
    "bn": "values-bn",
    "cs": "values-cs",
    "de": "values-de",
    "el": "values-el",
    "es": "values-es",
    "fa": "values-fa",
    "fr": "values-fr",
    "hi": "values-hi",
    "hu": "values-hu",
    "id": "values-in",
    "it": "values-it",
    "he": "values-iw",
    "ja": "values-ja",
    "ko": "values-ko",
    "nl": "values-nl",
    "pl": "values-pl",
    "pt": "values-pt",
    "ro": "values-ro",
    "ru": "values-ru",
    "sv": "values-sv",
    "th": "values-th",
    "tr": "values-tr",
    "uk": "values-uk",
    "ur": "values-ur",
    "vi": "values-vi",
    "zh": "values-zh",
}

PROTECTED_TERMS = [
    "Tailware",
    "Tailscale",
    "Headscale",
    "MagicDNS",
    "Taildrop",
    "tailnet",
    "Wear OS",
    "Android",
    "ADB",
    "VPN",
    "LAN",
    "DNS",
    "IPv4",
    "IPv6",
]

FORMAT_PATTERN = re.compile(
    r"%(?:(\d+)\$)?([-#+ 0,(<]*(?:\d+)?(?:\.\d+)?[tT]?[a-zA-Z])"
)
RESOURCE_REFERENCE_PATTERN = re.compile(
    r"R\.(string|plurals)\s*\.\s*([A-Za-z0-9_]+)"
)
BIDI_CONTROL_PATTERN = re.compile("[\u202a-\u202e\u2066-\u2069]")
REQUIRED_PLURAL_QUANTITIES = {
    "ar": {"zero", "one", "two", "few", "many", "other"},
    # Android lint requires every CLDR quantity even though Czech "many" is fractional-only.
    "cs": {"one", "few", "many", "other"},
    # CLDR selects "many" for whole millions in these languages, so an Android integer
    # quantity can reach it even though ordinary small counts use one/other.
    "es": {"one", "many", "other"},
    "fr": {"one", "many", "other"},
    "he": {"one", "two", "other"},
    "id": {"other"},
    "it": {"one", "many", "other"},
    "ja": {"other"},
    "ko": {"other"},
    "pl": {"one", "few", "many", "other"},
    "pt": {"one", "many", "other"},
    "ro": {"one", "few", "other"},
    "ru": {"one", "few", "many", "other"},
    "th": {"other"},
    "uk": {"one", "few", "many", "other"},
    "vi": {"other"},
    "zh": {"other"},
}
DEFAULT_PLURAL_QUANTITIES = {"one", "other"}
INTEGER_MILLION_MANY_LOCALES = {"es", "fr", "it", "pt"}


@dataclass(frozen=True)
class Resource:
    kind: str
    values: dict[str, str]
    translatable: bool

    @property
    def placeholder_signatures(self) -> dict[str, list[str]]:
        return {
            quantity: sorted(
                f"{position or ''}${kind}"
                for position, kind in FORMAT_PATTERN.findall(text)
                if kind.lower() != "n"
            )
            for quantity, text in self.values.items()
        }


def read_catalog(path: Path) -> dict[str, Resource]:
    root = ET.parse(path).getroot()
    resources: dict[str, Resource] = {}
    for element in root:
        if element.tag not in {"string", "plurals", "string-array"}:
            continue
        name = element.attrib.get("name")
        if not name:
            raise ValueError(f"{path}: resource without a name")
        if name in resources:
            raise ValueError(f"{path}: duplicate resource {name}")
        translatable = element.attrib.get("translatable", "true").lower() != "false"
        if element.tag == "string":
            values = {"other": "".join(element.itertext())}
        elif element.tag == "string-array":
            values = {
                str(index): "".join(item.itertext())
                for index, item in enumerate(element.findall("item"))
            }
        else:
            values = {}
            for item in element.findall("item"):
                quantity = item.attrib.get("quantity")
                if not quantity:
                    raise ValueError(f"{path}: plural {name} has an item without quantity")
                if quantity in values:
                    raise ValueError(f"{path}: plural {name} repeats quantity {quantity}")
                values[quantity] = "".join(item.itertext())
        resources[name] = Resource(element.tag, values, translatable)
    return resources


def configured_locales() -> list[str]:
    root = ET.parse(LOCALES_CONFIG).getroot()
    android_name = "{http://schemas.android.com/apk/res/android}name"
    return [element.attrib[android_name] for element in root.findall("locale")]


def configured_locale_tags() -> list[str]:
    """Return canonical IETF BCP 47 localeConfig tags in display order."""
    return list(LOCALE_DIRECTORIES)


def unexpected_locale_directories(actual_directories: set[str]) -> list[str]:
    expected_directories = {
        directory for locale, directory in LOCALE_DIRECTORIES.items() if locale != "en"
    }
    return sorted(actual_directories - expected_directories)


def validate_locale_directory_set(errors: list[str]) -> None:
    actual_directories = {
        directory.name
        for directory in RES.iterdir()
        if directory.is_dir()
        and directory.name.startswith("values-")
        and (directory / "wear_strings.xml").exists()
    }
    unexpected = unexpected_locale_directories(actual_directories)
    if unexpected:
        errors.append(
            f"{RES}: unexpected Tailware locale directories: {', '.join(unexpected)}"
        )


def validate_resource_text(path: Path, key: str, resource: Resource, errors: list[str]) -> None:
    for quantity, text in resource.values.items():
        if not text.strip():
            errors.append(f"{path}: {key}/{quantity} is empty")
        if "${" in text or "$date" in text:
            errors.append(f"{path}: {key}/{quantity} contains a Kotlin interpolation token")
        if BIDI_CONTROL_PATTERN.search(text):
            errors.append(f"{path}: {key}/{quantity} contains a forbidden bidi control")


def validate_plural_quantities(
    locale: str,
    path: Path,
    key: str,
    resource: Resource,
    errors: list[str],
) -> None:
    required_quantities = REQUIRED_PLURAL_QUANTITIES.get(
        locale, DEFAULT_PLURAL_QUANTITIES
    )
    missing_quantities = sorted(required_quantities - set(resource.values))
    if missing_quantities:
        errors.append(
            f"{path}: plural {key} is missing CLDR quantities "
            f"{', '.join(missing_quantities)} for {locale}"
        )


def validate_locale(
    locale: str,
    path: Path,
    base: dict[str, Resource],
    errors: list[str],
) -> None:
    catalog = read_catalog(path)
    expected = {key for key, value in base.items() if value.translatable}
    actual = {key for key, value in catalog.items() if value.translatable}
    missing = sorted(expected - actual)
    extra = sorted(actual - expected)
    if missing:
        errors.append(f"{path}: missing {len(missing)} keys: {', '.join(missing)}")
    if extra:
        errors.append(f"{path}: unexpected {len(extra)} keys: {', '.join(extra)}")

    for key in sorted(expected & actual):
        source = base[key]
        target = catalog[key]
        validate_resource_text(path, key, target, errors)
        if source.kind != target.kind:
            errors.append(f"{path}: {key} changed type {source.kind} -> {target.kind}")
            continue
        if target.kind == "plurals" and "other" not in target.values:
            errors.append(f"{path}: plural {key} has no 'other' quantity")
        if target.kind == "plurals":
            validate_plural_quantities(locale, path, key, target, errors)
        if target.kind == "string-array" and set(target.values) != set(source.values):
            errors.append(
                f"{path}: array {key} entries {sorted(target.values)} != "
                f"{sorted(source.values)}"
            )
        source_signature = source.placeholder_signatures.get("other")
        for quantity, signature in target.placeholder_signatures.items():
            expected_signature = (
                source_signature
                if source_signature is not None
                else source.placeholder_signatures.get(quantity, [])
            )
            if signature != expected_signature:
                errors.append(
                    f"{path}: {key}/{quantity} placeholders {signature} != "
                    f"{expected_signature}"
                )
        for quantity, target_text in target.values.items():
            source_text = source.values.get(quantity, source.values.get("other", ""))
            for term in PROTECTED_TERMS:
                if term in source_text and term not in target_text:
                    errors.append(
                        f"{path}: {key}/{quantity} must preserve protected term {term!r}"
                    )


def validate_cross_file_duplicates(errors: list[str]) -> None:
    for locale, directory in LOCALE_DIRECTORIES.items():
        if locale == "en":
            continue
        wear_path = RES / directory / "wear_strings.xml"
        paths = [
            wear_path,
            RES / directory / "strings.xml",
            RES / directory / MANAGED_CATALOG_FILENAME,
        ]
        catalogs = [(path, read_catalog(path)) for path in paths if path.exists()]
        for index, (left_path, left) in enumerate(catalogs):
            for right_path, right in catalogs[index + 1 :]:
                left_resources = {(resource.kind, key) for key, resource in left.items()}
                right_resources = {(resource.kind, key) for key, resource in right.items()}
                duplicates = sorted(left_resources & right_resources)
                if duplicates:
                    errors.append(
                        f"{directory}: duplicate resources across {left_path.name} and "
                        f"{right_path.name}: "
                        f"{', '.join(f'{kind}/{key}' for kind, key in duplicates)}"
                    )


def managed_configuration_references(errors: list[str]) -> dict[str, str]:
    references: dict[str, str] = {}
    root = ET.parse(APP_RESTRICTIONS).getroot()
    for restriction in root.iter("restriction"):
        for attribute in ("title", "description", "entries"):
            value = restriction.attrib.get(f"{ANDROID_ATTRIBUTE}{attribute}")
            if value is None:
                continue
            match = re.fullmatch(r"@(string|array)/([A-Za-z0-9_]+)", value)
            if match is None:
                errors.append(
                    f"{APP_RESTRICTIONS}: {attribute} must use a localizable resource, "
                    f"found {value!r}"
                )
                continue
            resource_type, key = match.groups()
            kind = "string" if resource_type == "string" else "string-array"
            previous = references.setdefault(key, kind)
            if previous != kind:
                errors.append(
                    f"{APP_RESTRICTIONS}: {key} is referenced as both {previous} and {kind}"
                )
    return references


def base_managed_configuration_catalog(
    references: dict[str, str], errors: list[str]
) -> dict[str, Resource]:
    catalog: dict[str, Resource] = {}
    for path in sorted((RES / "values").glob("*.xml")):
        for key, resource in read_catalog(path).items():
            if key not in references:
                continue
            # Android resource types have separate namespaces. A restriction
            # title can legitimately share its name with a non-visible value array.
            if resource.kind != references[key]:
                continue
            if key in catalog:
                errors.append(f"{path}: managed resource {key} is defined more than once")
                continue
            catalog[key] = resource
    for key, expected_kind in sorted(references.items()):
        resource = catalog.get(key)
        if resource is None:
            errors.append(f"{APP_RESTRICTIONS}: referenced resource {key} is missing")
        elif resource.kind != expected_kind:
            errors.append(
                f"{APP_RESTRICTIONS}: {key} expects {expected_kind}, found {resource.kind}"
            )
    return catalog


def validate_managed_configuration_locales(
    allow_missing: bool, errors: list[str]
) -> int:
    references = managed_configuration_references(errors)
    base = base_managed_configuration_catalog(references, errors)
    if set(base) != set(references):
        return len(references)
    for key, resource in base.items():
        validate_resource_text(APP_RESTRICTIONS, key, resource, errors)
    for locale, directory in LOCALE_DIRECTORIES.items():
        if locale == "en":
            continue
        path = RES / directory / MANAGED_CATALOG_FILENAME
        if not path.exists():
            if not allow_missing:
                errors.append(f"{path}: managed-configuration catalog is missing")
            continue
        validate_locale(locale, path, base, errors)
    return len(references)


def referenced_android_resources(source: str) -> set[tuple[str, str]]:
    return set(RESOURCE_REFERENCE_PATTERN.findall(source))


def validate_wear_resource_coverage(
    base: dict[str, Resource], errors: list[str]
) -> None:
    """Keep every Wear-visible resource inside the complete Tailware catalog."""
    source_paths = sorted(
        (ROOT / "android/src/main/java/com/tailscale/ipn/wear").rglob("*.kt")
    ) + [
        ROOT / "android/src/main/java/com/tailscale/ipn/IPNService.kt",
        ROOT / "android/src/main/java/com/tailscale/ipn/StartVPNWorker.java",
        ROOT / "android/src/main/java/com/tailscale/ipn/UseExitNodeWorker.kt",
        ROOT / "android/src/main/java/com/tailscale/ipn/ui/notifier/HealthNotifier.kt",
    ]
    references: set[tuple[str, str]] = set()
    for path in source_paths:
        references.update(referenced_android_resources(path.read_text(encoding="utf-8")))

    app_path = ROOT / "android/src/main/java/com/tailscale/ipn/App.kt"
    app_source = app_path.read_text(encoding="utf-8")
    start_marker = "  fun refreshTailwareNotificationChannels()"
    end_marker = "  fun getIsClientLoggingEnabled()"
    start = app_source.find(start_marker)
    end = app_source.find(end_marker, start + len(start_marker))
    if start < 0 or end < 0:
        errors.append(f"{app_path}: cannot locate the Tailware notification resource section")
    else:
        references.update(referenced_android_resources(app_source[start:end]))

    manifest_path = ROOT / "android/src/main/AndroidManifest.xml"
    manifest = ET.parse(manifest_path).getroot()
    android_name = "{http://schemas.android.com/apk/res/android}name"
    for service in manifest.findall("application/service"):
        if service.attrib.get(android_name) != ".wear.tile.TailwareTileService":
            continue
        for attribute in ("label", "description"):
            value = service.attrib.get(
                f"{{http://schemas.android.com/apk/res/android}}{attribute}", ""
            )
            match = re.fullmatch(r"@string/([A-Za-z0-9_]+)", value)
            if match:
                references.add(("string", match.group(1)))
            else:
                errors.append(
                    f"{manifest_path}: Tailware Tile {attribute} must reference @string/..."
                )

    for kind, key in sorted(references):
        resource = base.get(key)
        if resource is None:
            errors.append(
                f"Tailware Wear UI references {kind} {key!r} outside {BASE_CATALOG}"
            )
        elif resource.kind != kind:
            errors.append(
                f"Tailware Wear UI references {key!r} as {kind}, "
                f"but the catalog declares {resource.kind}"
            )

    unreferenced = sorted(set(base) - {key for _, key in references})
    if unreferenced:
        errors.append(
            f"{BASE_CATALOG}: unreferenced Tailware resources: {', '.join(unreferenced)}"
        )


def validate_kotlin_migration(errors: list[str]) -> None:
    files = [
        ROOT / "android/src/main/java/com/tailscale/ipn/wear/ui/TailwareApp.kt",
        ROOT / "android/src/main/java/com/tailscale/ipn/wear/tile/TailwareTileService.kt",
    ]
    forbidden = [
        "RUSSIAN_TRANSLATIONS",
        "russianTranslation(",
        "russianTileTranslation(",
        'tr(state, "',
        'localized(snapshot.language, "',
    ]
    for path in files:
        source = path.read_text(encoding="utf-8")
        for token in forbidden:
            if token in source:
                errors.append(f"{path}: obsolete localization token remains: {token}")

    visible_source_guards = {
        ROOT / "android/src/main/java/com/tailscale/ipn/StartVPNWorker.java": [
            "R.string.title_connection_failed",
            "R.string.body_open_tailscale",
        ],
        ROOT / "android/src/main/java/com/tailscale/ipn/UseExitNodeWorker.kt": [
            "R.string.vpn_is_not_ready_to_start",
            "R.string.use_exit_node_intent_failed",
            "exceptionOrNull()?.message",
        ],
        ROOT / "android/src/main/java/com/tailscale/ipn/ui/notifier/HealthNotifier.kt": [
            "sendNotification(warning.Title",
            ".setContentTitle(warning.Title)",
            ".setContentText(warning.Text)",
        ],
        ROOT / "android/src/main/java/com/tailscale/ipn/wear/ui/TailwareApp.kt": [
            "Locale.getDefault()",
            "FLAG_SECURE",
            "stringResource(",
            "value = BuildConfig.VERSION_NAME",
        ],
        ROOT / "android/src/main/java/com/tailscale/ipn/IPNService.kt": [
            ".setContentTitle(getString(",
            ".setContentText(getString(",
            "getString(R.string.wear_android_did_not_allow_tailware_to_start_the_vpn_service)",
        ],
        ROOT / "android/src/main/java/com/tailscale/ipn/App.kt": [
            "publishVpnServiceError(getString(",
            "getString(R.string.wear_unexpected_error_try_again)",
        ],
    }
    for path, tokens in visible_source_guards.items():
        source = path.read_text(encoding="utf-8")
        for token in tokens:
            if token in source:
                errors.append(f"{path}: user-visible localization bypass remains: {token}")


def validate_language_refresh_paths(errors: list[str]) -> None:
    view_model_path = (
        ROOT
        / "android/src/main/java/com/tailscale/ipn/wear/viewmodel/TailwareViewModel.kt"
    )
    view_model = view_model_path.read_text(encoding="utf-8")
    refresh_sections = [
        (
            "  fun setLanguage(",
            "  /** Imports a language selected through Android's per-app language settings. */",
        ),
        (
            "  fun syncLanguageFromSystem()",
            "  fun setOptionalNotificationsEnabled(",
        ),
    ]
    required_refreshes = [
        "app.refreshLocalizedNotifications()",
        "TailwareTileUpdates.request(app)",
    ]
    for start_marker, end_marker in refresh_sections:
        start = view_model.find(start_marker)
        end = view_model.find(end_marker, start + len(start_marker))
        if start < 0 or end < 0:
            errors.append(
                f"{view_model_path}: cannot locate language refresh section "
                f"{start_marker.strip()!r}"
            )
            continue
        section = view_model[start:end]
        for token in required_refreshes:
            if token not in section:
                errors.append(
                    f"{view_model_path}: {start_marker.strip()} must call {token}"
                )

    app_path = ROOT / "android/src/main/java/com/tailscale/ipn/wear/ui/TailwareApp.kt"
    app_source = app_path.read_text(encoding="utf-8")
    supplemental_start = app_source.find("  LaunchedEffect(\n      state.accountState,")
    supplemental_end = app_source.find("  val currentRoute =", supplemental_start)
    if supplemental_start < 0 or supplemental_end < 0:
        errors.append(f"{app_path}: cannot locate the Tile supplemental-state effect")
    else:
        supplemental = app_source[supplemental_start:supplemental_end]
        for token in ("state.language,", "LaunchedEffect(state.language)"):
            if token not in supplemental:
                errors.append(
                    f"{app_path}: Tile localization refresh must retain {token!r}"
                )


def english_export_payload(base: dict[str, Resource]) -> dict[str, object]:
    return {
        "_meta": {
            "sourceLanguage": "en",
            "targetLanguages": [tag for tag in LOCALE_DIRECTORIES if tag != "en"],
            "resourceCount": sum(resource.translatable for resource in base.values()),
            "protectedTerms": PROTECTED_TERMS,
            "placeholderRule": "Preserve every numbered placeholder and its type exactly.",
            "rtlLanguages": ["ar", "fa", "he", "ur"],
            "legacyResourceAliases": {"id": "values-in", "he": "values-iw"},
        },
        "resources": {
            key: {
                "type": resource.kind,
                "values": resource.values,
                "placeholders": resource.placeholder_signatures,
            }
            for key, resource in sorted(base.items())
            if resource.translatable
        },
    }


def validate_english_export_payload(
    base: dict[str, Resource],
    actual_payload: object,
    source: str,
    errors: list[str],
) -> None:
    if actual_payload != english_export_payload(base):
        errors.append(
            f"{source}: English export is stale; run "
            f"'python tools/validate-tailware-locales.py --write-catalog'"
        )


def validate_english_export(base: dict[str, Resource], errors: list[str]) -> None:
    if not ENGLISH_EXPORT.exists():
        errors.append(f"{ENGLISH_EXPORT}: English export is missing")
        return
    try:
        actual_payload = json.loads(ENGLISH_EXPORT.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        errors.append(f"{ENGLISH_EXPORT}: cannot read English export: {error}")
        return
    validate_english_export_payload(base, actual_payload, str(ENGLISH_EXPORT), errors)


def write_english_export(base: dict[str, Resource]) -> None:
    payload = english_export_payload(base)
    ENGLISH_EXPORT.parent.mkdir(parents=True, exist_ok=True)
    ENGLISH_EXPORT.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def run_fixtures() -> None:
    """Prove that integer-million CLDR categories cannot silently regress to one/other."""
    fixture_path = Path("<plural-fixture>")
    incomplete = Resource(
        kind="plurals",
        values={"one": "%1$d device", "other": "%1$d devices"},
        translatable=True,
    )
    for locale in sorted(INTEGER_MILLION_MANY_LOCALES):
        errors: list[str] = []
        validate_plural_quantities(
            locale,
            fixture_path,
            "wear_devices_in_tailnet",
            incomplete,
            errors,
        )
        expected = (
            f"{fixture_path}: plural wear_devices_in_tailnet is missing CLDR quantities "
            f"many for {locale}"
        )
        if errors != [expected]:
            raise AssertionError(
                f"CLDR integer-million fixture failed for {locale}: {errors!r}"
            )

    ordinary_errors: list[str] = []
    validate_plural_quantities(
        "de",
        fixture_path,
        "wear_devices_in_tailnet",
        incomplete,
        ordinary_errors,
    )
    if ordinary_errors:
        raise AssertionError(
            f"one/other plural fixture unexpectedly failed for de: {ordinary_errors!r}"
        )

    precision_placeholder = Resource(
        kind="string",
        values={"other": "%1$.1f ms"},
        translatable=True,
    )
    if precision_placeholder.placeholder_signatures != {"other": ["1$.1f"]}:
        raise AssertionError(
            "precision placeholder fixture was not parsed: "
            f"{precision_placeholder.placeholder_signatures!r}"
        )

    reference_fixture = """
        tr(state, R.string
            .wear_fixture)
        trQuantity(state, R.plurals.wear_fixture_count)
    """
    expected_references = {
        ("string", "wear_fixture"),
        ("plurals", "wear_fixture_count"),
    }
    actual_references = referenced_android_resources(reference_fixture)
    if actual_references != expected_references:
        raise AssertionError(
            f"Wear resource reference fixture failed: {actual_references!r}"
        )

    export_base = {
        "wear_fixture": Resource(
            kind="string",
            values={"other": "Fresh English source"},
            translatable=True,
        )
    }
    current_export = english_export_payload(export_base)
    current_errors: list[str] = []
    validate_english_export_payload(
        export_base,
        current_export,
        "<english-export-fixture>",
        current_errors,
    )
    if current_errors:
        raise AssertionError(
            f"current English export fixture unexpectedly failed: {current_errors!r}"
        )

    stale_export = json.loads(json.dumps(current_export))
    stale_export["resources"]["wear_fixture"]["values"]["other"] = "Stale English source"
    stale_errors: list[str] = []
    validate_english_export_payload(
        export_base,
        stale_export,
        "<english-export-fixture>",
        stale_errors,
    )
    if len(stale_errors) != 1 or "English export is stale" not in stale_errors[0]:
        raise AssertionError(
            f"stale English export fixture was not rejected: {stale_errors!r}"
        )

    unexpected_directories = unexpected_locale_directories(
        {"values-ar", "values-in", "values-iw", "values-fi"}
    )
    if unexpected_directories != ["values-fi"]:
        raise AssertionError(
            "unexpected locale directory fixture failed: "
            f"{unexpected_directories!r}"
        )

    restriction_errors: list[str] = []
    restriction_references = managed_configuration_references(restriction_errors)
    expected_kinds = {"string": 41, "string-array": 3}
    actual_kinds = {
        kind: list(restriction_references.values()).count(kind)
        for kind in expected_kinds
    }
    if restriction_errors or actual_kinds != expected_kinds:
        raise AssertionError(
            "managed-configuration reference fixture failed: "
            f"kinds={actual_kinds!r}, errors={restriction_errors!r}"
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--allow-missing",
        action="store_true",
        help="Validate locales that exist but do not fail for locale files not created yet.",
    )
    parser.add_argument(
        "--write-catalog",
        action="store_true",
        help="Regenerate docs/localization/tailware-english-catalog.json.",
    )
    parser.add_argument(
        "--run-fixtures",
        action="store_true",
        help="Run deterministic validator regression fixtures before validating catalogs.",
    )
    arguments = parser.parse_args()

    if arguments.run_fixtures:
        run_fixtures()

    errors: list[str] = []
    base = read_catalog(BASE_CATALOG)
    for key, resource in base.items():
        validate_resource_text(BASE_CATALOG, key, resource, errors)

    expected_tags = configured_locale_tags()
    actual_tags = configured_locales()
    if actual_tags != expected_tags:
        errors.append(
            f"{LOCALES_CONFIG}: locale order/set {actual_tags} does not match {expected_tags}"
        )
    validate_locale_directory_set(errors)

    for locale, directory in LOCALE_DIRECTORIES.items():
        if locale == "en":
            continue
        path = RES / directory / "wear_strings.xml"
        if not path.exists():
            if not arguments.allow_missing:
                errors.append(f"{path}: locale catalog is missing")
            continue
        validate_locale(locale, path, base, errors)

    managed_resource_count = validate_managed_configuration_locales(
        arguments.allow_missing, errors
    )
    validate_cross_file_duplicates(errors)
    validate_wear_resource_coverage(base, errors)
    validate_kotlin_migration(errors)
    validate_language_refresh_paths(errors)
    if arguments.write_catalog:
        write_english_export(base)
    validate_english_export(base, errors)

    if errors:
        print("\n".join(f"ERROR: {error}" for error in errors), file=sys.stderr)
        return 1
    print(
        f"Tailware localization catalog is valid: "
        f"{sum(resource.translatable for resource in base.values())} translatable resources, "
        f"{managed_resource_count} managed-configuration resources, "
        f"{len(LOCALE_DIRECTORIES)} languages."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
