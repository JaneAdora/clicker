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
