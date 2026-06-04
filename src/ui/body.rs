// src/ui/body.rs
use crate::app::App;
use crate::theme;
use ratatui::layout::{Constraint, Direction, Layout, Margin, Rect};
use ratatui::text::{Line, Span};
use ratatui::widgets::Paragraph;
use ratatui::Frame;

pub fn render(f: &mut Frame, area: Rect, app: &App) {
    // Manual 1-col side padding; no border (spec §7.3).
    let area = area.inner(Margin {
        horizontal: 1,
        vertical: 0,
    });

    let rows = Layout::default()
        .direction(Direction::Vertical)
        .constraints([Constraint::Min(7), Constraint::Length(1)])
        .split(area);

    f.render_widget(Paragraph::new(cheatsheet()), rows[0]);
    f.render_widget(Paragraph::new(volume_line(app)), rows[1]);
}

/// D-pad diagram (left) + button list (right), per spec §7.5.
fn cheatsheet() -> Vec<Line<'static>> {
    let cap = theme::pane_header_focused();
    let lbl = theme::dim();
    let raw = |s: &'static str| Span::raw(s);

    vec![
        Line::from(vec![raw("        \u{250c}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2510}")]),
        Line::from(vec![
            raw("        \u{2502}  "),
            Span::styled("\u{2191}", cap),
            raw("  \u{2502}        "),
            Span::styled("\u{23ce}", cap),
            Span::styled("  select", lbl),
        ]),
        Line::from(vec![
            raw("   \u{250c}\u{2500}\u{2500}\u{2500}\u{2500}\u{253c}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{253c}\u{2500}\u{2500}\u{2500}\u{2500}\u{2510}   "),
            Span::styled("m", cap),
            Span::styled("  mute", lbl),
        ]),
        Line::from(vec![
            raw("   \u{2502} "),
            Span::styled("\u{2190}", cap),
            raw("  \u{2502}  "),
            Span::styled("\u{25cf}", cap),
            raw("  \u{2502}  "),
            Span::styled("\u{2192}", cap),
            raw(" \u{2502}   "),
            Span::styled("+", cap),
            Span::styled("  vol up", lbl),
        ]),
        Line::from(vec![
            raw("   \u{2514}\u{2500}\u{2500}\u{2500}\u{2500}\u{253c}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{253c}\u{2500}\u{2500}\u{2500}\u{2500}\u{2518}   "),
            Span::styled("-", cap),
            Span::styled("  vol down", lbl),
        ]),
        Line::from(vec![
            raw("        \u{2502}  "),
            Span::styled("\u{2193}", cap),
            raw("  \u{2502}"),
        ]),
        Line::from(vec![raw("        \u{2514}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2518}")]),
    ]
}

/// Threshold-colored volume bar (spec §7.5): muted->dim; >=70->now(pink); else historical(lavender).
fn volume_line(app: &App) -> Line<'static> {
    let max = app.volume_max.max(1);
    let width = 20usize;
    let filled = (app.volume as usize * width) / max as usize;
    let bar: String = "\u{2588}".repeat(filled) + &"\u{2591}".repeat(width - filled);

    let style = if app.muted {
        theme::dim()
    } else if app.volume >= 70 {
        theme::now()
    } else {
        theme::historical()
    };

    let label = if app.muted {
        format!(" vol {:>3}  (muted)", app.volume)
    } else {
        format!(" vol {:>3}", app.volume)
    };

    Line::from(vec![
        Span::styled(format!(" {bar} "), style),
        Span::styled(label, style),
    ])
}
