"""Apply reviewed PT-PT and PT-BR copy for TV strings left in English.

Unlike auto-generated machine translations, these regional translations use
existing Starlit TV terminology. Only replace known English fallback values;
preserve all previously localized text and source formatting placeholders.

Usage: py -3 scripts/complete_tizen_portuguese.py --apply
"""
from __future__ import annotations

import argparse
import re
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree as ET
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "tizen/res"
FORMAT = re.compile(r"%(?:\d+\$)?[-+#0,.(]*\d*(?:\.\d+)?[diuoxXfFeEgGaAcCsSbBhH%]")

# Product names such as "Dolby Vision", "Prime Video", and legal attribution
# titles deliberately stay in their original form. These are not translation gaps.
PT_PT = {
    "profile_copy_settings_label": "Copiar definições",
    "profile_copy_settings_setup_title": "Definições do perfil",
    "profile_copy_settings_setup_subtitle": "Começa do zero ou copia uma única vez as definições da TV de outro perfil.",
    "profile_copy_settings_start_fresh": "Começar do zero",
    "profile_copy_settings_create_fresh": "Definições: começar do zero",
    "profile_copy_settings_title": "Copiar definições para %1$s",
    "profile_copy_settings_subtitle": "Escolhe um perfil de origem. As definições de TV compatíveis vão substituir as deste perfil uma única vez.",
    "profile_copy_settings_from": "Copiar de",
    "profile_copy_settings_action": "Copiar definições",
    "profile_copy_settings_credentials_off": "Chaves API: não copiar",
    "profile_copy_settings_credentials_on": "Chaves API: copiar chaves em falta",
    "profile_copy_settings_details": "As chaves API existentes são mantidas. OAuth, add-ons, plug-ins, disposição da página inicial, PIN, histórico, biblioteca, transferências e definições específicas do dispositivo não são alterados.",
    "profile_copy_settings_exclusions": "OAuth, add-ons, plug-ins, disposição da página inicial, PIN, histórico, biblioteca, transferências e definições específicas do dispositivo não são alterados.",
    "profile_copy_settings_success": "Definições copiadas de %1$s para %2$s.",
    "profile_copy_settings_error": "Não foi possível copiar as definições. Verifica a ligação e tenta novamente.",
    "profile_copy_settings_created_success": "Perfil criado com as definições de %1$s.",
    "profile_copy_settings_created_error": "Perfil criado, mas não foi possível copiar as definições. Podes voltar a tentar nas Opções de perfil.",
    "profile_copy_settings_credentials_title": "Copiar credenciais de %1$s?",
    "profile_copy_settings_success_title": "Definições copiadas",
    "profile_copy_settings_error_title": "Não foi possível copiar as definições",
    "settings_stream_badges_in_player_title": "Mostrar no leitor",
    "settings_stream_badges_in_player_description": "Mostra emblemas do vídeo e do áudio em reprodução por baixo do título do leitor. Requer um pacote de emblemas que reconheça texto simples, como 2160p ou Atmos.",
    "settings_stream_player_badges_title": "Emblemas do leitor",
    "settings_stream_player_badges_description": "Escolhe o pacote de emblemas utilizado pelo leitor.",
    "settings_stream_player_badges_same": "Igual aos emblemas das fontes",
    "settings_p2p_profile_title": "Perfil de torrents",
    "settings_p2p_profile_soft_description": "Usa menos ligações a outros utilizadores para reduzir o consumo de bateria e a carga da rede.",
    "settings_p2p_profile_balanced_description": "Equilíbrio recomendado entre a rapidez de arranque e a estabilidade da ligação.",
    "settings_p2p_profile_fast_description": "Usa mais ligações para torrents difíceis ou com débito elevado.",
    "settings_p2p_cache_size_title": "Tamanho da cache de torrents",
    "settings_p2p_cache_size_description": "Dados descarregados de torrents guardados neste dispositivo para voltar a reproduzir mais depressa.",
    "settings_p2p_cache_none": "Sem cache persistente",
    "settings_p2p_clear_cache_title": "Limpar a cache de torrents",
    "settings_p2p_clear_cache_usage": "%1$s utilizados atualmente",
    "settings_p2p_clear_cache_usage_pending": "O espaço utilizado é medido depois de iniciar o P2P; seleciona para limpar os dados de torrents em cache.",
    "settings_p2p_clear_cache_clearing": "A limpar dados de torrents inativos…",
    "settings_p2p_clear_cache_done": "Foram libertados %1$s de dados de torrents inativos",
    "advanced_section_network_tests": "Testes de rede",
    "stream_test_card_title": "Testar a velocidade da última transmissão reproduzida",
    "stream_test_no_stream": "Não existe uma reprodução anterior. Reproduz um vídeo para executar testes de diagnóstico ao respetivo servidor.",
    "stream_test_btn_measuring_baseline": "A medir a referência...",
    "stream_test_btn_measuring_parallel1": "A medir em paralelo (1 MB)...",
    "stream_test_btn_measuring_parallel4": "A medir em paralelo (4 MB)...",
    "stream_test_btn_measuring_parallel8": "A medir em paralelo (8 MB)...",
    "stream_test_btn_measuring_parallel16": "A medir em paralelo (16 MB)...",
    "stream_test_label_baseline": "Ligação única padrão (referência)",
    "stream_test_label_parallel1": "Ligações em paralelo (blocos de 1 MB)",
    "stream_test_label_parallel4": "Ligações em paralelo (blocos de 4 MB)",
    "stream_test_label_parallel8": "Ligações em paralelo (blocos de 8 MB)",
    "stream_test_label_parallel16": "Ligações em paralelo (blocos de 16 MB)",
    "stream_test_error_connection": "A ligação falhou. O endereço da última transmissão pode ter expirado ou estar inacessível. Reproduz novamente o vídeo e volta a tentar.",
    "settings_p2p_cache_unsupported": "Não disponível nesta TV.",
}
PT_BR = {
    "genre_game_show": "Programa de auditório",
    "profile_copy_settings_credentials_title": "Copiar credenciais de %1$s?",
    "profile_copy_settings_success_title": "Configurações copiadas",
    "profile_copy_settings_error_title": "Não foi possível copiar as configurações",
    "settings_p2p_cache_unsupported": "Não disponível nesta TV.",
}


def read_strings(folder: Path) -> dict[str, str]:
    return {element.attrib["name"]: "".join(element.itertext())
            for element in ET.parse(folder / "strings.xml").getroot()
            if element.tag == "string"}


def check_formats(english: str, translation: str) -> None:
    expected = Counter(part for part in FORMAT.findall(english) if part != "%%")
    actual = Counter(part for part in FORMAT.findall(translation) if part != "%%")
    if actual != expected:
        raise ValueError(f"Format token mismatch: {expected} != {actual}")
    if english.count(r"\n") != translation.count(r"\n"):
        raise ValueError("Line-break mismatch")


def apply(locale: str, overrides: dict[str, str], *, write: bool) -> int:
    target = RES / f"values-{locale}" / "strings.xml"
    english = read_strings(RES / "values")
    existing = read_strings(RES / f"values-{locale}")
    raw = target.read_text(encoding="utf-8-sig")
    updated = raw
    count = 0
    for key, translation in overrides.items():
        if key not in english or key not in existing:
            raise ValueError(f"Unexpected or missing {locale} string key: {key}")
        check_formats(english[key], translation)
        if existing[key] == translation:
            continue
        if existing[key] != english[key] and not (
            locale == "pt-pt" and key == "settings_stream_badges_in_player_title" and
            existing[key] == "Show in player"
        ):
            raise ValueError(f"Refusing to overwrite an already localized {locale} key: {key}")
        regex = re.compile(r'(<string\s+name="' + re.escape(key) + r'"[^>]*>)(.*?)(</string>)')
        replacement = escape(translation, {'"': '&quot;'})
        updated, match_count = regex.subn(lambda m: m[1] + replacement + m[3], updated)
        if match_count != 1:
            raise ValueError(f"Could not safely replace {locale}/{key} in XML ({match_count} matches)")
        count += 1
    ET.fromstring(updated)
    if write and updated != raw:
        target.write_text(updated, encoding="utf-8", newline="")
    print(f"{locale}: {count} English UI strings {'translated' if write else 'ready to translate'}")
    return count


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Write review-complete Portuguese translations")
    args = parser.parse_args()
    apply("pt-pt", PT_PT, write=args.apply)
    apply("pt-br", PT_BR, write=args.apply)


if __name__ == "__main__":
    main()
