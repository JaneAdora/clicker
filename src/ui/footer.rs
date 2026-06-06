// src/ui/footer.rs — slim status bar. The TV buttons all live in the body now, so
// the footer only carries clicker's own control (quit) plus the transient toast.
use crate::theme;
use ratatui::layout::Rect;
use ratatui::text::{Line, Span};
use ratatui::widgets::Paragraph;
use ratatui::Frame;

pub fn render(f: &mut Frame, area: Rect, transient: Option<&str>) {
    let mut lines = vec![Line::from(vec![
        Span::styled("[q]", theme::pane_header_focused()),
        Span::styled(" quit", theme::dim()),
    ])];
    if let Some(msg) = transient {
        lines.push(Line::from(Span::styled(msg.to_string(), theme::status())));
    }
    f.render_widget(Paragraph::new(lines), area);
}
