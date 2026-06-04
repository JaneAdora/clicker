// src/ui/footer.rs
use crate::theme;
use ratatui::layout::Rect;
use ratatui::text::{Line, Span};
use ratatui::widgets::Paragraph;
use ratatui::Frame;

const SEP: &str = "  \u{2502}  "; // "  │  " in dim()

pub fn render(f: &mut Frame, area: Rect, transient: Option<&str>) {
    // (keycap, label) pairs — full keymap (spec §5).
    let hints: [(&str, &str); 9] = [
        ("\u{2191}\u{2193}\u{2190}\u{2192}", " d-pad"),
        ("\u{23ce}", " select"),
        ("\u{232b}", " back"),
        ("+/-", " vol"),
        ("m", " mute"),
        ("space", " play"),
        ("o", " menu"),
        ("?", " help"),
        ("q", " quit"),
    ];

    let mut spans: Vec<Span> = Vec::new();
    for (i, (cap, label)) in hints.iter().enumerate() {
        if i > 0 {
            spans.push(Span::styled(SEP, theme::dim()));
        }
        spans.push(Span::styled(*cap, theme::pane_header_focused()));
        spans.push(Span::raw(*label));
    }

    let mut lines = vec![Line::from(spans)];
    if let Some(msg) = transient {
        lines.push(Line::from(Span::styled(msg.to_string(), theme::status())));
    }

    f.render_widget(Paragraph::new(lines), area);
}
