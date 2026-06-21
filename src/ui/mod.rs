// src/ui/mod.rs
pub mod body;
pub mod footer;
pub mod header;
pub mod modal;

use crate::app::App;
use crate::types::InputMode;
use ratatui::layout::{Constraint, Direction, Layout};
use ratatui::Frame;

pub fn render(f: &mut Frame, app: &App) {
    let toast = app.transient_str();
    // Header/footer grow to 2 lines when a toast is present (roam pattern).
    let chrome = if toast.is_some() { 2 } else { 1 };

    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(chrome),
            Constraint::Min(1),
            Constraint::Length(chrome),
        ])
        .split(f.area());

    header::render(f, chunks[0], &app.tv_name, app.link, toast);
    body::render(f, chunks[1], app);
    footer::render(f, chunks[2], toast);

    // Modal overlay drawn LAST so it sits on top.
    match &app.mode {
        InputMode::Normal => {}
        InputMode::HostEntry { entered } => {
            let area = modal::centered_rect(f.area(), 50, 40);
            modal::render_host(f, area, entered);
        }
        InputMode::PinEntry { entered, error } => {
            let area = modal::centered_rect(f.area(), 50, 40);
            modal::render_pin(f, area, entered, error.as_deref());
        }
        InputMode::KeyProbe { entered, last } => {
            let area = modal::centered_rect(f.area(), 66, 64);
            modal::render_probe(f, area, entered, last.as_deref());
        }
        InputMode::TextInput {
            buffer,
            field_active,
        } => {
            let area = modal::centered_rect(f.area(), 60, 36);
            modal::render_text_input(f, area, buffer, *field_active);
        }
        InputMode::DevicePicker { rows, selected } => {
            let area = modal::centered_rect(f.area(), 70, 60);
            modal::render_picker(f, area, rows, *selected);
        }
    }
}
