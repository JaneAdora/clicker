// src/keymap.rs — Normal-mode key -> Action. Mirrors the on-screen RC802V layout.
// Rules: every binding is a SINGLE unshifted key (so nothing requires Shift), and
// every key is shown on screen (ui/body.rs), so bindings need only be unique, not
// memorable.
use crate::types::{RemoteKey, TvCmd};
use crossterm::event::{KeyCode, KeyEvent};

/// What a keypress resolves to.
pub enum Action {
    Cmd(TvCmd),
    Quit,
    Launch(char),  // digit 0-9 -> app shortcut, resolved against config in app.rs
    EnterTextMode, // `k` — live IME text entry
    EnterProbe,    // `/` — raw keycode probe (debug)
}

pub fn map_normal(key: KeyEvent) -> Option<Action> {
    use KeyCode::*;
    let k = |rk: RemoteKey| Some(Action::Cmd(TvCmd::Key(rk)));

    match key.code {
        // D-pad + select (Enter is the centre of the D-pad)
        Up => k(RemoteKey::Up),
        Down => k(RemoteKey::Down),
        Left => k(RemoteKey::Left),
        Right => k(RemoteKey::Right),
        Enter => k(RemoteKey::Select),

        // Back — both Esc and Backspace
        Esc | Backspace => k(RemoteKey::Back),

        // Top function buttons
        Char('h') => k(RemoteKey::Home),
        Char('p') => k(RemoteKey::Power),
        Char('s') => k(RemoteKey::Settings),
        Char('o') => k(RemoteKey::Menu),
        Char('i') => k(RemoteKey::Input),
        Char('v') => k(RemoteKey::Assist),
        Char('k') => Some(Action::EnterTextMode),

        // Volume (up = `+` or unshifted `=`; down = `-`) + mute
        Char('+') | Char('=') => k(RemoteKey::VolUp),
        Char('-') => k(RemoteKey::VolDown),
        Char('m') => k(RemoteKey::Mute),

        // Channel
        PageUp => k(RemoteKey::ChannelUp),
        PageDown => k(RemoteKey::ChannelDown),

        // App shortcuts (digits 1-0; resolved against config in app.rs)
        Char(c) if c.is_ascii_digit() => Some(Action::Launch(c)),

        // Media transport (bonus — not on the physical RC802V)
        Char(' ') => k(RemoteKey::PlayPause),
        Char('x') => k(RemoteKey::Stop),
        Char(',') => k(RemoteKey::Rewind),
        Char('.') => k(RemoteKey::FastForward),
        Char(';') => k(RemoteKey::Prev),
        Char('\'') => k(RemoteKey::Next),

        // clicker itself
        Char('q') => Some(Action::Quit),
        Char('/') => Some(Action::EnterProbe), // debug: send a raw keycode

        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crossterm::event::KeyModifiers;

    fn ev(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    #[test]
    fn up_arrow_is_dpad_up() {
        assert!(matches!(
            map_normal(ev(KeyCode::Up)),
            Some(Action::Cmd(TvCmd::Key(RemoteKey::Up)))
        ));
    }

    #[test]
    fn enter_is_select() {
        assert!(matches!(
            map_normal(ev(KeyCode::Enter)),
            Some(Action::Cmd(TvCmd::Key(RemoteKey::Select)))
        ));
    }

    #[test]
    fn esc_and_backspace_are_back() {
        assert!(matches!(
            map_normal(ev(KeyCode::Esc)),
            Some(Action::Cmd(TvCmd::Key(RemoteKey::Back)))
        ));
        assert!(matches!(
            map_normal(ev(KeyCode::Backspace)),
            Some(Action::Cmd(TvCmd::Key(RemoteKey::Back)))
        ));
    }

    #[test]
    fn plus_or_equals_is_volume_up() {
        assert!(matches!(
            map_normal(ev(KeyCode::Char('+'))),
            Some(Action::Cmd(TvCmd::Key(RemoteKey::VolUp)))
        ));
        assert!(matches!(
            map_normal(ev(KeyCode::Char('='))),
            Some(Action::Cmd(TvCmd::Key(RemoteKey::VolUp)))
        ));
    }

    #[test]
    fn p_is_power_no_shift() {
        assert!(matches!(
            map_normal(ev(KeyCode::Char('p'))),
            Some(Action::Cmd(TvCmd::Key(RemoteKey::Power)))
        ));
    }

    #[test]
    fn digits_map_to_launch() {
        assert!(matches!(
            map_normal(ev(KeyCode::Char('1'))),
            Some(Action::Launch('1'))
        ));
        assert!(matches!(
            map_normal(ev(KeyCode::Char('0'))),
            Some(Action::Launch('0'))
        ));
    }

    #[test]
    fn q_quits() {
        assert!(matches!(map_normal(ev(KeyCode::Char('q'))), Some(Action::Quit)));
    }

    #[test]
    fn slash_enters_probe() {
        assert!(matches!(
            map_normal(ev(KeyCode::Char('/'))),
            Some(Action::EnterProbe)
        ));
    }
}
