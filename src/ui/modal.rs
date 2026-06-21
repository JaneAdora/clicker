// src/ui/modal.rs
use crate::theme;
use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Borders, Clear, Paragraph};
use ratatui::Frame;

/// Centered sub-rect, percent of parent in each axis. Copied from roam/src/ui/mod.rs.
pub fn centered_rect(parent: Rect, percent_x: u16, percent_y: u16) -> Rect {
    let v = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Percentage((100 - percent_y) / 2),
            Constraint::Percentage(percent_y),
            Constraint::Percentage((100 - percent_y) / 2),
        ])
        .split(parent);
    Layout::default()
        .direction(Direction::Horizontal)
        .constraints([
            Constraint::Percentage((100 - percent_x) / 2),
            Constraint::Percentage(percent_x),
            Constraint::Percentage((100 - percent_x) / 2),
        ])
        .split(v[1])[1]
}

/// First-run PIN entry. Input masked with '•'; error line in alert() on a bad PIN.
pub fn render_pin(f: &mut Frame, area: Rect, entered: &str, error: Option<&str>) {
    let block = Block::default()
        .title(Line::from(Span::styled(
            " pair with TV ",
            theme::pane_header_focused(),
        )))
        .borders(Borders::ALL)
        .border_style(theme::pane_header());
    f.render_widget(Clear, area);
    let inner = block.inner(area);
    f.render_widget(block, area);

    let masked: String = "\u{2022}".repeat(entered.chars().count()); // •

    let mut lines = vec![
        Line::from(Span::styled(
            "Enter the PIN shown on the TV:",
            theme::dim(),
        )),
        Line::from(Span::styled(
            format!(" {masked}\u{2588}"), // trailing block as a cursor
            theme::pane_header(),
        )),
    ];
    if let Some(err) = error {
        lines.push(Line::from(Span::raw("")));
        lines.push(Line::from(Span::styled(err.to_string(), theme::alert())));
    }
    f.render_widget(Paragraph::new(lines), inner);
}

/// Keycode probe (debug): type any Android keycode, Enter fires it. Includes a
/// cheat-sheet of codes worth trying for TV-dependent buttons like input/source —
/// the direct-HDMI codes (243-246) often work where the generic input (178) doesn't.
pub fn render_probe(f: &mut Frame, area: Rect, entered: &str, last: Option<&str>) {
    let block = Block::default()
        .title(Line::from(Span::styled(
            " keycode probe ",
            theme::pane_header_focused(),
        )))
        .borders(Borders::ALL)
        .border_style(theme::pane_header());
    f.render_widget(Clear, area);
    let inner = block.inner(area);
    f.render_widget(block, area);

    let mut lines = vec![
        Line::from(Span::styled("keycode + Enter:", theme::dim())),
        Line::from(Span::styled(
            format!(" > {entered}\u{2588}"),
            theme::pane_header(),
        )),
    ];
    if let Some(l) = last {
        lines.push(Line::from(Span::styled(l.to_string(), theme::status())));
    }
    lines.push(Line::from(Span::raw("")));
    lines.push(Line::from(Span::styled("try for input/source:", theme::dim())));
    for (code, name) in [
        ("178", "TV_INPUT (generic)"),
        ("243", "HDMI 1"),
        ("244", "HDMI 2"),
        ("245", "HDMI 3"),
        ("170", "TV"),
        ("176", "Settings"),
        ("172", "Guide"),
    ] {
        lines.push(Line::from(vec![
            Span::styled(format!(" {code:<5}"), theme::pane_header_focused()),
            Span::styled(name.to_string(), theme::dim()),
        ]));
    }
    lines.push(Line::from(Span::raw("")));
    lines.push(Line::from(Span::styled("esc to close", theme::dim())));
    f.render_widget(Paragraph::new(lines), inner);
}

/// Live typing mode: what you type mirrors to the TV's focused field via IME.
/// Shows the buffer with a cursor, and a hint when no field is focused yet.
pub fn render_text_input(f: &mut Frame, area: Rect, buffer: &str, field_active: bool) {
    let block = Block::default()
        .title(Line::from(Span::styled(
            " type on TV ",
            theme::pane_header_focused(),
        )))
        .borders(Borders::ALL)
        .border_style(theme::pane_header());
    f.render_widget(Clear, area);
    let inner = block.inner(area);
    f.render_widget(block, area);

    let mut lines = vec![
        Line::from(Span::styled(
            "Type — it appears on the TV. Enter sends, Esc cancels.",
            theme::dim(),
        )),
        Line::from(Span::styled(
            format!(" {buffer}\u{2588}"), // trailing block as a cursor
            theme::pane_header(),
        )),
    ];
    if !field_active {
        lines.push(Line::from(Span::raw("")));
        lines.push(Line::from(Span::styled(
            "focus a search box on the TV first",
            theme::alert(),
        )));
    }
    f.render_widget(Paragraph::new(lines), inner);
}

/// Device picker: saved TVs (●), discovered TVs (○), and a manual-entry row.
/// The selected row is highlighted with a `›` caret.
pub fn render_picker(f: &mut Frame, area: Rect, rows: &[crate::types::PickerRow], selected: usize) {
    let block = Block::default()
        .title(Line::from(Span::styled(
            " devices ",
            theme::pane_header_focused(),
        )))
        .borders(Borders::ALL)
        .border_style(theme::pane_header());
    f.render_widget(Clear, area);
    let inner = block.inner(area);
    f.render_widget(block, area);

    let mut lines = vec![
        Line::from(Span::styled(
            "\u{2191}/\u{2193} select  ·  Enter connect  ·  Esc close",
            theme::dim(),
        )),
        Line::from(Span::raw("")),
    ];
    for (i, row) in rows.iter().enumerate() {
        let caret = if i == selected { "\u{203a}" } else { " " };
        let text = if row.manual {
            format!(" {caret} \u{ff0b} {}", row.name)
        } else {
            let dot = if row.saved { "\u{25cf}" } else { "\u{25cb}" };
            format!(" {caret} {dot} {}   {}", row.name, row.host)
        };
        let style = if i == selected {
            theme::active_row()
        } else {
            theme::historical()
        };
        lines.push(Line::from(Span::styled(text, style)));
    }
    if rows.len() <= 1 {
        lines.push(Line::from(Span::raw("")));
        lines.push(Line::from(Span::styled(
            "scanning for TVs\u{2026}",
            theme::dim(),
        )));
    }
    f.render_widget(Paragraph::new(lines), inner);
}

/// First-run host (TV IP) entry. NOT masked — an IP address is not a secret.
/// Distinct title/prompt from the PIN modal so the two are never confused.
pub fn render_host(f: &mut Frame, area: Rect, entered: &str) {
    let block = Block::default()
        .title(Line::from(Span::styled(
            " connect to TV ",
            theme::pane_header_focused(),
        )))
        .borders(Borders::ALL)
        .border_style(theme::pane_header());
    f.render_widget(Clear, area);
    let inner = block.inner(area);
    f.render_widget(block, area);

    let lines = vec![
        Line::from(Span::styled(
            "Enter the TV's IP address:",
            theme::dim(),
        )),
        Line::from(Span::styled(
            format!(" {entered}\u{2588}"), // trailing block as a cursor; shown verbatim
            theme::pane_header(),
        )),
    ];
    f.render_widget(Paragraph::new(lines), inner);
}
