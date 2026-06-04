// src/ui/header.rs
use crate::theme;
use crate::types::LinkState;
use ratatui::layout::Rect;
use ratatui::text::{Line, Span};
use ratatui::widgets::Paragraph;
use ratatui::Frame;

pub fn render(
    f: &mut Frame,
    area: Rect,
    tv_name: &str,
    link: LinkState,
    transient: Option<&str>,
) {
    let (glyph, glyph_style) = match link {
        LinkState::Connected => ("\u{25cf}", theme::link_ok()), // ●
        LinkState::Pairing | LinkState::Connecting => ("\u{25d0}", theme::link_pending()), // ◐
        LinkState::Down => ("\u{25cb}", theme::link_down()), // ○
    };

    let title = Line::from(vec![
        Span::styled("clicker ", theme::pane_header_focused()),
        Span::styled(tv_name.to_string(), theme::pane_header()),
        Span::raw("  "),
        Span::styled(glyph, glyph_style),
    ]);

    let mut lines = vec![title];
    if let Some(msg) = transient {
        lines.push(Line::from(Span::styled(msg.to_string(), theme::status())));
    }

    f.render_widget(Paragraph::new(lines), area);
}
