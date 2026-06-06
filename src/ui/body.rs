// src/ui/body.rs — the on-screen remote face, laid out to mirror the TCL RC802V:
// a vertical 2-column remote with Enter (OK) in the centre of the D-pad and every
// key shown as a labelled cap, so nothing needs to be memorised (no `?` pop-out).
use crate::app::App;
use crate::theme;
use ratatui::layout::{Alignment, Constraint, Direction, Layout, Margin, Rect};
use ratatui::text::{Line, Span};
use ratatui::widgets::Paragraph;
use ratatui::Frame;

/// A keycap like `[h]` — brackets dim, the key itself magenta-bold (suite cap style).
fn cap(key: &str) -> Vec<Span<'static>> {
    vec![
        Span::styled("[", theme::dim()),
        Span::styled(key.to_string(), theme::pane_header_focused()),
        Span::styled("]", theme::dim()),
    ]
}

/// One labelled button:  `[key] <icon> Name`  (icon in pink accent, name lavender).
fn btn(key: &str, icon: &str, name: &str) -> Line<'static> {
    let mut spans = cap(key);
    spans.push(Span::raw(" "));
    spans.push(Span::styled(icon.to_string(), theme::now()));
    spans.push(Span::raw(" "));
    spans.push(Span::styled(name.to_string(), theme::historical()));
    Line::from(spans)
}

pub fn render(f: &mut Frame, area: Rect, app: &App) {
    let area = area.inner(Margin {
        horizontal: 1,
        vertical: 0,
    });

    let rows = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(2), // Power/Settings, Home/Options
            Constraint::Length(1), // gap
            Constraint::Length(7), // D-pad (Enter in centre)
            Constraint::Length(1), // gap
            Constraint::Length(4), // Back/Input, Voice/Type, Volume/Channel, Mute
            Constraint::Length(1), // gap
            Constraint::Length(3), // apps
            Constraint::Length(1), // gap
            Constraint::Length(6), // media
            Constraint::Length(1), // gap
            Constraint::Length(1), // volume bar
            Constraint::Min(0),
        ])
        .split(area);

    // top function block
    two_col(
        f,
        rows[0],
        vec![btn("p", "\u{23fb}", "Power"), btn("h", "\u{2302}", "Home")],
        vec![
            btn("s", "\u{2699}", "Settings"),
            btn("o", "\u{2261}", "Options"),
        ],
    );

    // D-pad with Enter (OK) in the centre — the keys ARE the cells.
    f.render_widget(
        Paragraph::new(dpad()).alignment(Alignment::Center),
        rows[2],
    );

    // middle function block
    two_col(
        f,
        rows[4],
        vec![
            btn("esc", "\u{21a9}", "Back"),
            btn("v", "(o)", "Voice"),
            btn("+/-", "<))", "Volume"),
            btn("m", "<x", "Mute"),
        ],
        vec![
            btn("i", "\u{229e}", "Input"),
            btn("k", "\u{2328}", "Type"),
            btn("PgUp/Dn", "\u{25ad}", "Channel"),
            Line::raw(""),
        ],
    );

    // app shortcuts — not limited to the physical remote's hotkeys; any installed
    // app can get a launch shortcut via the protocol's app-link request.
    two_col(
        f,
        rows[6],
        vec![
            app_line("\u{24c3}", "Netflix", "1"),
            app_line("\u{24b9}", "Disney+", "3"),
            app_line("\u{24c9}", "TCL apps", "5"),
        ],
        vec![
            app_line("\u{24ce}", "YouTube", "2"),
            app_line("\u{24c2}", "Max", "4"),
            Line::raw(""),
        ],
    );

    // media (bonus — not on the physical RC802V, kept for streaming)
    f.render_widget(
        Paragraph::new(media()).alignment(Alignment::Center),
        rows[8],
    );

    // live volume bar
    f.render_widget(
        Paragraph::new(volume_line(app)).alignment(Alignment::Center),
        rows[10],
    );
}

/// Split `rect` into two columns and stack `left`/`right` lines in each.
fn two_col(f: &mut Frame, rect: Rect, left: Vec<Line<'static>>, right: Vec<Line<'static>>) {
    let cols = Layout::default()
        .direction(Direction::Horizontal)
        .constraints([Constraint::Percentage(50), Constraint::Percentage(50)])
        .split(rect);
    f.render_widget(Paragraph::new(left), cols[0]);
    f.render_widget(Paragraph::new(right), cols[1]);
}

/// The D-pad: arrows are the keys, `\u{23ce}` (Enter) sits in the centre = OK/select.
fn dpad() -> Vec<Line<'static>> {
    let k = theme::pane_header_focused();
    let r = |s: &'static str| Span::raw(s);
    vec![
        Line::from(r("\u{250c}\u{2500}\u{2500}\u{2500}\u{2510}")),
        Line::from(vec![r("\u{2502} "), Span::styled("\u{2191}", k), r(" \u{2502}")]),
        Line::from(r("\u{250c}\u{2500}\u{2500}\u{2500}\u{253c}\u{2500}\u{2500}\u{2500}\u{253c}\u{2500}\u{2500}\u{2500}\u{2510}")),
        Line::from(vec![
            r("\u{2502} "),
            Span::styled("\u{2190}", k),
            r(" \u{2502} "),
            Span::styled("\u{23ce}", k), // Enter = OK, dead centre
            r(" \u{2502} "),
            Span::styled("\u{2192}", k),
            r(" \u{2502}"),
        ]),
        Line::from(r("\u{2514}\u{2500}\u{2500}\u{2500}\u{253c}\u{2500}\u{2500}\u{2500}\u{253c}\u{2500}\u{2500}\u{2500}\u{2518}")),
        Line::from(vec![r("\u{2502} "), Span::styled("\u{2193}", k), r(" \u{2502}")]),
        Line::from(r("\u{2514}\u{2500}\u{2500}\u{2500}\u{2518}")),
    ]
}

/// One app-launch tile: `<logo> Name [key]` — circled letter as a little logo.
fn app_line(logo: &str, name: &str, key: &str) -> Line<'static> {
    let mut s = vec![
        Span::styled(logo.to_string(), theme::now()),
        Span::raw(" "),
        Span::styled(name.to_string(), theme::historical()),
        Span::raw(" "),
    ];
    s.extend(cap(key));
    Line::from(s)
}

/// Media transport (bonus). Glyphs: ▶ play, ■ stop, plus ASCII transport.
fn media() -> Vec<Line<'static>> {
    let ic = theme::now();
    let lbl = theme::historical();
    let item = |g: &'static str, name: &'static str, key: &str| -> Vec<Span<'static>> {
        let mut s = vec![Span::styled(g, ic), Span::raw(" "), Span::styled(name, lbl), Span::raw(" ")];
        s.extend(cap(key));
        s
    };
    vec![
        Line::from(item("\u{25b6}", "Play/Pause", "space")),
        Line::from(item("\u{25a0}", "Stop", "x")),
        Line::raw(""),
        Line::from({
            let mut r = item("<<", "Rew", ",");
            r.push(Span::raw("      "));
            r.extend(item(">>", "FF", "."));
            r
        }),
        Line::raw(""),
        Line::from({
            let mut r = item("|<", "Prev", ";");
            r.push(Span::raw("     "));
            r.extend(item(">|", "Next", "'"));
            r
        }),
    ]
}

/// Threshold-coloured volume bar: muted->dim; >=70->pink; else lavender.
fn volume_line(app: &App) -> Line<'static> {
    let max = app.volume_max.max(1);
    let width = 16usize;
    let filled = (app.volume as usize * width) / max as usize;
    let bar: String = "\u{2588}".repeat(filled) + &"\u{2591}".repeat(width.saturating_sub(filled));
    let style = if app.muted {
        theme::dim()
    } else if app.volume >= 70 {
        theme::now()
    } else {
        theme::historical()
    };
    let label = if app.muted {
        format!(" {:>3} muted", app.volume)
    } else {
        format!(" {:>3}", app.volume)
    };
    Line::from(vec![
        Span::styled(format!("{bar} "), style),
        Span::styled(label, style),
    ])
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;
    use ratatui::backend::TestBackend;
    use ratatui::Terminal;

    #[test]
    fn snapshot_remote_layout() {
        let (tx, _rx) = tokio::sync::mpsc::channel(1);
        let mut app = App::new(Config::default(), tx);
        app.tv_name = "Living Room".into();
        app.link = crate::types::LinkState::Connected;
        app.volume = 32;
        app.volume_max = 100;

        // full screen: header + body + footer
        let mut terminal = Terminal::new(TestBackend::new(44, 44)).unwrap();
        terminal.draw(|f| crate::ui::render(f, &app)).unwrap();
        println!("\n{}", terminal.backend());
    }
}
