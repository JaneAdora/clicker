// src/theme.rs
use ratatui::style::{Color, Modifier, Style};
use std::sync::OnceLock;

struct Palette {
    pink: Color,
    lavender: Color,
    magenta: Color,
}
static PALETTE: OnceLock<Palette> = OnceLock::new();

/// Core palette, overridable via ~/.config/dashboard-suite/theme.toml.
fn palette() -> &'static Palette {
    PALETTE.get_or_init(|| {
        let mut p = Palette {
            pink: Color::Rgb(0xe8, 0x8b, 0x9f),
            lavender: Color::Rgb(0xc5, 0xa3, 0xff),
            magenta: Color::Rgb(0xff, 0x6e, 0xc7),
        };
        if let Some(cfg) = suite_theme_path() {
            if let Ok(s) = std::fs::read_to_string(cfg) {
                for line in s.lines() {
                    let t = line.trim();
                    if t.starts_with('#') {
                        continue;
                    }
                    if let Some((k, v)) = t.split_once('=') {
                        if let Some(c) = parse_hex(v.trim().trim_matches('"')) {
                            match k.trim() {
                                "pink" => p.pink = c,
                                "lavender" => p.lavender = c,
                                "magenta" => p.magenta = c,
                                _ => {}
                            }
                        }
                    }
                }
            }
        }
        p
    })
}

fn suite_theme_path() -> Option<std::path::PathBuf> {
    if let Ok(x) = std::env::var("XDG_CONFIG_HOME") {
        return Some(std::path::PathBuf::from(x).join("dashboard-suite/theme.toml"));
    }
    std::env::var_os("HOME")
        .map(|h| std::path::PathBuf::from(h).join(".config/dashboard-suite/theme.toml"))
}

fn parse_hex(s: &str) -> Option<Color> {
    let s = s.strip_prefix('#').unwrap_or(s);
    if s.len() != 6 {
        return None;
    }
    Some(Color::Rgb(
        u8::from_str_radix(&s[0..2], 16).ok()?,
        u8::from_str_radix(&s[2..4], 16).ok()?,
        u8::from_str_radix(&s[4..6], 16).ok()?,
    ))
}

pub fn pink() -> Color {
    palette().pink
}
pub fn lavender() -> Color {
    palette().lavender
}
pub fn magenta() -> Color {
    palette().magenta
}

// Link-state colors: the 3-color palette has no green, so reuse glance's
// sage (ok) and amber (warn) verbatim as non-overridable raw consts (spec §7.2).
fn sage() -> Color {
    Color::Rgb(0x9b, 0xe1, 0x95)
}
fn amber() -> Color {
    Color::Rgb(0xff, 0xd9, 0x6e)
}

// --- named style builders (render code never touches Color::Rgb directly) ---

/// Pane title / modal border: lavender bold.
pub fn pane_header() -> Style {
    Style::default().fg(lavender()).add_modifier(Modifier::BOLD)
}
/// Focused pane title / keycaps: magenta bold.
pub fn pane_header_focused() -> Style {
    Style::default().fg(magenta()).add_modifier(Modifier::BOLD)
}
/// Active selection row: pink bold.
pub fn active_row() -> Style {
    Style::default().fg(pink()).add_modifier(Modifier::BOLD)
}
/// Dim hint / separator: lavender dim.
pub fn dim() -> Style {
    Style::default().fg(lavender()).add_modifier(Modifier::DIM)
}
/// Status / toast line: magenta.
pub fn status() -> Style {
    Style::default().fg(magenta())
}
/// "now" / hot value (vol >= 70): pink.
pub fn now() -> Style {
    Style::default().fg(pink())
}
/// "historical" / cool value: lavender.
pub fn historical() -> Style {
    Style::default().fg(lavender())
}
/// Error / alert: magenta bold.
pub fn alert() -> Style {
    Style::default().fg(magenta()).add_modifier(Modifier::BOLD)
}

// --- link state (spec §7.2) ---

/// Connected: sage bold.
pub fn link_ok() -> Style {
    Style::default().fg(sage()).add_modifier(Modifier::BOLD)
}
/// Connecting / pairing: amber.
pub fn link_pending() -> Style {
    Style::default().fg(amber())
}
/// Disconnected: alert (magenta bold).
pub fn link_down() -> Style {
    alert()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_hex_with_and_without_hash() {
        assert_eq!(parse_hex("#e88b9f"), Some(Color::Rgb(0xe8, 0x8b, 0x9f)));
        assert_eq!(parse_hex("c5a3ff"), Some(Color::Rgb(0xc5, 0xa3, 0xff)));
    }

    #[test]
    fn parse_hex_rejects_bad_input() {
        assert_eq!(parse_hex("fff"), None); // wrong length
        assert_eq!(parse_hex("#gggggg"), None); // non-hex digit
        assert_eq!(parse_hex(""), None);
    }
}
